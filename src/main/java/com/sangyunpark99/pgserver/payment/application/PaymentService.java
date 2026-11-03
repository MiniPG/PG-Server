package com.sangyunpark99.pgserver.payment.application;

import com.sangyunpark99.pgserver.common.time.TimeProvider;
import com.sangyunpark99.pgserver.common.exception.CardCancelFailedException;
import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.common.exception.ErrorCode;
import com.sangyunpark99.pgserver.common.exception.PgException;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.domain.CardInfo;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final CardCompanyClient cardCompanyClient;
    private final PaymentMetrics metrics;
    private final TimeProvider timeProvider;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public Payment create(CreatePaymentCommand command) {
        Payment payment = Payment.create(
                generatePaymentKey(),
                command.orderId(),
                command.orderName(),
                command.mId(),
                command.amount(),
                command.partialCancelable(),
                timeProvider.now()
        );
        paymentRepository.save(payment);
        log.atInfo().setMessage("결제 생성")
                .addKeyValue("paymentKey", payment.getPaymentKey())
                .addKeyValue("orderId", payment.getOrderId())
                .addKeyValue("amount", payment.getTotalAmount())
                .log();
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getByPaymentKey(String mId, String paymentKey) {
        return findOwned(mId, paymentKey);
    }

    @Transactional(readOnly = true)
    public Payment getByOrderId(String mId, String orderId) {
        return paymentRepository.findByMerchantAndOrderId(mId, orderId)
                .orElseThrow(() -> new PgException(ErrorCode.NOT_FOUND_PAYMENT));
    }

    public Payment confirm(ConfirmPaymentCommand command) {
        ApprovalRequest request = transactionTemplate.execute(status -> startApproval(command));

        CardApprovalResult result;
        try {
            result = cardCompanyClient.approve(request.transactionKey(), request.paymentKey(), request.amount());
        } catch (CardCompanyUnavailableException e) {
            metrics.approve(PaymentMetrics.UNAVAILABLE);
            log.atWarn().setMessage("결제 승인 결과 모름: 카드사 응답 없음")
                    .addKeyValue("paymentKey", request.paymentKey())
                    .addKeyValue("transactionKey", request.transactionKey())
                    .addKeyValue("cause", e.getCause() == null ? e.getMessage() : e.getCause().toString())
                    .log();
            throw e;
        }

        return transactionTemplate.execute(status -> finishApproval(command.mId(), request, result));
    }

    private ApprovalRequest startApproval(ConfirmPaymentCommand command) {
        Payment payment = findOwned(command.mId(), command.paymentKey());
        verify(payment, command);

        int claimed = paymentRepository.changeStatusIf(payment.getId(), PaymentStatus.IN_PROGRESS, PaymentStatus.APPROVING);
        if (claimed == 0) {
            throw rejectByStatus(findOwned(command.mId(), command.paymentKey()).getStatus());
        }

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), payment.getMId(), TransactionType.APPROVE,
                payment.getTotalAmount(), null, timeProvider.now()
        );
        transactionRepository.save(tx);
        return new ApprovalRequest(payment.getPaymentKey(), tx.getTransactionKey(), payment.getTotalAmount());
    }

    private Payment finishApproval(String mId, ApprovalRequest request, CardApprovalResult result) {
        Payment payment = findOwned(mId, request.paymentKey());
        PaymentTransaction tx = findTransaction(mId, request.transactionKey());

        if (result.approved()) {
            payment.approve(new CardInfo(result.issuerCode(), result.maskedNumber(), result.approveNo()), timeProvider.now());
            tx.complete(timeProvider.now());
            metrics.approve(PaymentMetrics.APPROVED);
            log.atInfo().setMessage("결제 승인")
                    .addKeyValue("paymentKey", payment.getPaymentKey())
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("result", PaymentMetrics.APPROVED)
                    .log();
        } else {
            payment.abort(result.failureCode(), result.failureMessage());
            tx.fail(timeProvider.now());
            metrics.approve(PaymentMetrics.REJECTED);
            log.atInfo().setMessage("결제 승인 거절")
                    .addKeyValue("paymentKey", payment.getPaymentKey())
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("result", PaymentMetrics.REJECTED)
                    .addKeyValue("failureCode", result.failureCode())
                    .log();
        }
        return payment;
    }

    private record ApprovalRequest(String paymentKey, String transactionKey, long amount) {
    }

    public Payment cancel(CancelPaymentCommand command) {
        CancelRequest request = transactionTemplate.execute(status -> startCancel(command));

        CardCancelResult result;
        try {
            result = cardCompanyClient.cancel(request.transactionKey(), request.approveNo(), request.amount());
        } catch (CardCompanyUnavailableException e) {
            metrics.cancel(PaymentMetrics.UNAVAILABLE);
            log.atWarn().setMessage("결제 취소 결과 모름: 카드사 응답 없음")
                    .addKeyValue("paymentKey", request.paymentKey())
                    .addKeyValue("transactionKey", request.transactionKey())
                    .addKeyValue("cancelAmount", request.amount())
                    .addKeyValue("cause", e.getCause() == null ? e.getMessage() : e.getCause().toString())
                    .log();
            throw e;
        }

        if (!result.canceled()) {
            transactionTemplate.executeWithoutResult(status -> rejectCancel(command.mId(), request));
            metrics.cancel(PaymentMetrics.REJECTED);
            log.atInfo().setMessage("결제 취소 거절")
                    .addKeyValue("paymentKey", request.paymentKey())
                    .addKeyValue("transactionKey", request.transactionKey())
                    .addKeyValue("cancelAmount", request.amount())
                    .addKeyValue("failureCode", result.failureCode())
                    .log();
            throw new CardCancelFailedException(result.failureCode(), result.failureMessage());
        }

        return transactionTemplate.execute(status -> finishCancel(command.mId(), request));
    }

    private CancelRequest startCancel(CancelPaymentCommand command) {
        Payment payment = findOwned(command.mId(), command.paymentKey());

        if (command.cancelReason() == null || command.cancelReason().isBlank()) {
            throw new PgException(ErrorCode.CANCEL_REASON_REQUIRED);
        }
        long cancelAmount = command.cancelAmount() == null ? payment.getBalanceAmount() : command.cancelAmount();
        payment.validateCancel(cancelAmount);

        int deducted = paymentRepository.deductBalance(payment.getId(), cancelAmount);
        if (deducted == 0) {
            findOwned(command.mId(), command.paymentKey()).validateCancel(cancelAmount);
            throw new PgException(ErrorCode.EXCEED_CANCEL_AMOUNT);
        }

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), payment.getMId(), TransactionType.CANCEL,
                cancelAmount, command.cancelReason(), timeProvider.now()
        );
        transactionRepository.save(tx);
        return new CancelRequest(payment.getId(), payment.getPaymentKey(), tx.getTransactionKey(),
                payment.getCard().getApproveNo(), cancelAmount);
    }

    private void rejectCancel(String mId, CancelRequest request) {
        paymentRepository.restoreBalance(request.paymentId(), request.amount());
        findTransaction(mId, request.transactionKey()).fail(timeProvider.now());
    }

    private Payment finishCancel(String mId, CancelRequest request) {
        findTransaction(mId, request.transactionKey()).complete(timeProvider.now());
        Payment payment = findOwned(mId, request.paymentKey());
        metrics.cancel(PaymentMetrics.CANCELED);
        log.atInfo().setMessage("결제 취소")
                .addKeyValue("paymentKey", payment.getPaymentKey())
                .addKeyValue("transactionKey", request.transactionKey())
                .addKeyValue("cancelAmount", request.amount())
                .addKeyValue("balanceAmount", payment.getBalanceAmount())
                .log();
        return payment;
    }

    private PaymentTransaction findTransaction(String mId, String transactionKey) {
        return transactionRepository.findByMerchantAndTransactionKey(mId, transactionKey)
                .orElseThrow(() -> new IllegalStateException("거래가 없습니다. transactionKey=" + transactionKey));
    }

    private record CancelRequest(Long paymentId, String paymentKey, String transactionKey, String approveNo, long amount) {
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> findCancels(String paymentKey) {
        return transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL);
    }

    private Payment findOwned(String mId, String paymentKey) {
        return paymentRepository.findByMerchantAndPaymentKey(mId, paymentKey)
                .orElseThrow(() -> new PgException(ErrorCode.NOT_FOUND_PAYMENT));
    }

    private void verify(Payment payment, ConfirmPaymentCommand command) {
        if (!payment.getOrderId().equals(command.orderId())) {
            throw new PgException(ErrorCode.ORDER_ID_MISMATCH);
        }
        if (payment.getTotalAmount() != command.amount()) {
            throw new PgException(ErrorCode.AMOUNT_MISMATCH);
        }

        if (payment.getStatus() != PaymentStatus.IN_PROGRESS) {
            throw rejectByStatus(payment.getStatus());
        }
        if (payment.isExpired(timeProvider.now())) {
            throw new PgException(ErrorCode.PAYMENT_EXPIRED);
        }
    }

    private PgException rejectByStatus(PaymentStatus status) {
        return switch (status) {
            case APPROVING -> new PgException(ErrorCode.APPROVAL_IN_PROGRESS);
            case EXPIRED -> new PgException(ErrorCode.PAYMENT_EXPIRED);
            default -> new PgException(ErrorCode.INVALID_STATUS, "승인할 수 없는 상태입니다. status=" + status);
        };
    }

    private String generateTransactionKey() {
        return "tx_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String generatePaymentKey() {
        return "pk_" + UUID.randomUUID().toString().replace("-", "");
    }
}