package com.sangyunpark99.pgserver.payment.application;

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

import java.time.LocalDateTime;
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

    @Transactional
    public Payment create(CreatePaymentCommand command) {
        Payment payment = Payment.create(
                generatePaymentKey(),
                command.orderId(),
                command.orderName(),
                command.mId(),
                command.amount(),
                command.partialCancelable(),
                LocalDateTime.now()
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

    @Transactional(noRollbackFor = CardCompanyUnavailableException.class)
    public Payment confirm(ConfirmPaymentCommand command) {
        Payment payment = findOwned(command.mId(), command.paymentKey());

        verify(payment, command);

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), payment.getMId(), TransactionType.APPROVE,
                payment.getTotalAmount(), null, LocalDateTime.now()
        );
        transactionRepository.save(tx);

        CardApprovalResult result;
        try {
            result = cardCompanyClient.approve(tx.getTransactionKey(), payment.getPaymentKey(), payment.getTotalAmount());
        } catch (CardCompanyUnavailableException e) {
            metrics.approve(PaymentMetrics.UNAVAILABLE);
            log.atWarn().setMessage("결제 승인 결과 모름: 카드사 응답 없음")
                    .addKeyValue("paymentKey", payment.getPaymentKey())
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("cause", e.getCause() == null ? e.getMessage() : e.getCause().toString())
                    .log();
            throw e;
        }

        if (result.approved()) {
            payment.approve(new CardInfo(result.issuerCode(), result.maskedNumber(), result.approveNo()), LocalDateTime.now());
            tx.complete(LocalDateTime.now());
            metrics.approve(PaymentMetrics.APPROVED);
            log.atInfo().setMessage("결제 승인")
                    .addKeyValue("paymentKey", payment.getPaymentKey())
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("result", PaymentMetrics.APPROVED)
                    .log();
        } else {
            payment.abort(result.failureCode(), result.failureMessage());
            tx.fail(LocalDateTime.now());
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

    @Transactional(noRollbackFor = {CardCancelFailedException.class, CardCompanyUnavailableException.class})
    public Payment cancel(CancelPaymentCommand command) {
        Payment payment = findOwned(command.mId(), command.paymentKey());

        if (command.cancelReason() == null || command.cancelReason().isBlank()) {
            throw new PgException(ErrorCode.CANCEL_REASON_REQUIRED);
        }
        long cancelAmount = command.cancelAmount() == null ? payment.getBalanceAmount() : command.cancelAmount();
        payment.validateCancel(cancelAmount);

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), payment.getMId(), TransactionType.CANCEL,
                cancelAmount, command.cancelReason(), LocalDateTime.now()
        );
        transactionRepository.save(tx);

        CardCancelResult result;
        try {
            result = cardCompanyClient.cancel(tx.getTransactionKey(), payment.getCard().getApproveNo(), cancelAmount);
        } catch (CardCompanyUnavailableException e) {
            metrics.cancel(PaymentMetrics.UNAVAILABLE);
            log.atWarn().setMessage("결제 취소 결과 모름: 카드사 응답 없음")
                    .addKeyValue("paymentKey", payment.getPaymentKey())
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("cancelAmount", cancelAmount)
                    .addKeyValue("cause", e.getCause() == null ? e.getMessage() : e.getCause().toString())
                    .log();
            throw e;
        }

        if (!result.canceled()) {
            tx.fail(LocalDateTime.now());
            metrics.cancel(PaymentMetrics.REJECTED);
            log.atInfo().setMessage("결제 취소 거절")
                    .addKeyValue("paymentKey", payment.getPaymentKey())
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("cancelAmount", cancelAmount)
                    .addKeyValue("failureCode", result.failureCode())
                    .log();
            throw new CardCancelFailedException(result.failureCode(), result.failureMessage());
        }
        payment.cancel(cancelAmount);
        tx.complete(LocalDateTime.now());
        metrics.cancel(PaymentMetrics.CANCELED);
        log.atInfo().setMessage("결제 취소")
                .addKeyValue("paymentKey", payment.getPaymentKey())
                .addKeyValue("transactionKey", tx.getTransactionKey())
                .addKeyValue("cancelAmount", cancelAmount)
                .addKeyValue("balanceAmount", payment.getBalanceAmount())
                .log();
        return payment;
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> findCancels(String paymentKey) {
        return transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL);
    }

    private Payment findOwned(String mId, String paymentKey) {
        return paymentRepository.findByPaymentKey(paymentKey)
                .filter(payment -> payment.getMId().equals(mId))
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
            throw new PgException(ErrorCode.INVALID_STATUS, "승인할 수 없는 상태입니다. status=" + payment.getStatus());
        }
        if (payment.isExpired(LocalDateTime.now())) {
            throw new PgException(ErrorCode.PAYMENT_EXPIRED);
        }
    }

    private String generateTransactionKey() {
        return "tx_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String generatePaymentKey() {
        return "pk_" + UUID.randomUUID().toString().replace("-", "");
    }
}