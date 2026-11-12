package com.sangyunpark99.pgserver.payment.application;

import com.sangyunpark99.pgserver.card.CardCallBulkhead;
import com.sangyunpark99.pgserver.common.time.TimeProvider;
import com.sangyunpark99.pgserver.common.exception.CardCancelFailedException;
import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.common.exception.ErrorCode;
import com.sangyunpark99.pgserver.common.exception.PgException;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.domain.CardInfo;
import com.sangyunpark99.pgserver.payment.domain.IdempotencyRecord;
import com.sangyunpark99.pgserver.payment.domain.IdempotentOperation;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.IdempotencyRecordRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
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
    private final CardCallBulkhead cardCallBulkhead;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

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

    public Payment confirm(ConfirmPaymentCommand command) {
        return cardCallBulkhead.execute(() -> doConfirm(command));
    }

    public PaymentResponse cancel(CancelPaymentCommand command) {
        return cardCallBulkhead.execute(() -> doCancel(command));
    }

    public Payment getByPaymentKey(String mId, String paymentKey) {
        return findOwned(mId, paymentKey);
    }

    public Payment getByOrderId(String mId, String orderId) {
        return paymentRepository.findByMerchantAndOrderId(mId, orderId)
                .orElseThrow(() -> new PgException(ErrorCode.NOT_FOUND_PAYMENT));
    }

    private Payment doConfirm(ConfirmPaymentCommand command) {
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
            return reverseApproval(command.mId(), request, e);
        }

        return transactionTemplate.execute(status -> finishApproval(command.mId(), request, result));
    }

    private Payment reverseApproval(String mId, ApprovalRequest request, CardCompanyUnavailableException approvalFailure) {
        try {
            cardCompanyClient.reverse(request.transactionKey(), request.paymentKey(), request.amount());
        } catch (CardCompanyUnavailableException e) {
            log.atWarn().setMessage("망취소 실패: 승인 결과 모름")
                    .addKeyValue("paymentKey", request.paymentKey())
                    .addKeyValue("transactionKey", request.transactionKey())
                    .addKeyValue("cause", e.getCause() == null ? e.getMessage() : e.getCause().toString())
                    .log();
            throw approvalFailure;
        }

        Payment payment = transactionTemplate.execute(status -> abortReversedApproval(mId, request));
        metrics.approve(PaymentMetrics.REVERSED);
        log.atInfo().setMessage("결제 망취소")
                .addKeyValue("paymentKey", request.paymentKey())
                .addKeyValue("transactionKey", request.transactionKey())
                .addKeyValue("result", PaymentMetrics.REVERSED)
                .log();
        return payment;
    }

    private Payment abortReversedApproval(String mId, ApprovalRequest request) {
        int finished = transactionRepository.finishIfRequested(request.transactionId(), TransactionStatus.FAILED, timeProvider.now());
        Payment payment = findOwned(mId, request.paymentKey());
        if (finished == 1) {
            payment.abortByReversal();
        }
        return payment;
    }

    private ApprovalRequest startApproval(ConfirmPaymentCommand command) {
        Payment payment = findOwned(command.mId(), command.paymentKey());
        verify(payment, command);

        int claimed = paymentRepository.changeStatusIf(payment.getId(), PaymentStatus.IN_PROGRESS, PaymentStatus.APPROVING);
        if (claimed == 0) {
            throw rejectByStatus(findLatest(payment.getId()).getStatus());
        }

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), payment.getMId(), TransactionType.APPROVE,
                payment.getTotalAmount(), null, timeProvider.now()
        );
        transactionRepository.save(tx);
        payment.startApproving();
        return new ApprovalRequest(payment, tx.getId(), tx.getTransactionKey());
    }

    private Payment finishApproval(String mId, ApprovalRequest request, CardApprovalResult result) {
        LocalDateTime now = timeProvider.now();
        TransactionStatus next = result.approved() ? TransactionStatus.DONE : TransactionStatus.FAILED;
        int finished = transactionRepository.finishIfRequested(request.transactionId(), next, now);
        if (finished == 0) {
            return findOwned(mId, request.paymentKey());
        }

            if (result.approved()) {
            Payment payment = request.payment();
            CardInfo card = new CardInfo(result.issuerCode(), result.maskedNumber(), result.approveNo());
            int approved = paymentRepository.approveIfApproving(payment.getId(),
                    card.getIssuerCode(), card.getMaskedNumber(), card.getApproveNo(), now);
            if (approved == 0) {
                throw new IllegalStateException("승인 중이 아닌 결제를 승인하려 했습니다. paymentKey=" + payment.getPaymentKey());
            }
            payment.approve(card, now);
            metrics.approve(PaymentMetrics.APPROVED);
            log.atInfo().setMessage("결제 승인")
                    .addKeyValue("paymentKey", payment.getPaymentKey())
                    .addKeyValue("transactionKey", request.transactionKey())
                    .addKeyValue("result", PaymentMetrics.APPROVED)
                    .log();
            return payment;
        }

        Payment payment = findOwned(mId, request.paymentKey());
        payment.abort(result.failureCode(), result.failureMessage());
        metrics.approve(PaymentMetrics.REJECTED);
        log.atInfo().setMessage("결제 승인 거절")
                .addKeyValue("paymentKey", payment.getPaymentKey())
                .addKeyValue("transactionKey", request.transactionKey())
                .addKeyValue("result", PaymentMetrics.REJECTED)
                .addKeyValue("failureCode", result.failureCode())
                .log();
        return payment;
    }

    private record ApprovalRequest(Payment payment, Long transactionId, String transactionKey) {
        String paymentKey() {
            return payment.getPaymentKey();
        }

        long amount() {
            return payment.getTotalAmount();
        }
    }

    private PaymentResponse doCancel(CancelPaymentCommand command) {
        CancelRequest request;
        try {
            request = transactionTemplate.execute(status -> startCancel(command));
        } catch (DataIntegrityViolationException e) {
            IdempotencyRecord winner = findRecord(command);
            if (winner == null) {
                throw e;
            }
            if (!winner.sameRequest(fingerprintOf(command))) {
                throw new PgException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return replay(command.mId(), winner);
        }

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
            findLatest(payment.getId()).validateCancel(cancelAmount);
            throw new PgException(ErrorCode.EXCEED_CANCEL_AMOUNT);
        }

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), payment.getMId(), TransactionType.CANCEL,
                cancelAmount, command.cancelReason(), timeProvider.now()
        );
        transactionRepository.save(tx);
        Long recordId = null;
        if (command.idempotencyKey() != null) {
            recordId = idempotencyRecordRepository.save(IdempotencyRecord.of(command.mId(), command.idempotencyKey(),
                    IdempotentOperation.CANCEL, fingerprintOf(command), payment.getPaymentKey(), tx.getId(),
                    timeProvider.now())).getId();
        }
        return new CancelRequest(payment.getId(), payment.getPaymentKey(), tx.getId(), tx.getTransactionKey(),
                payment.getCard().getApproveNo(), cancelAmount, recordId);
    }

    private void rejectCancel(String mId, CancelRequest request) {
        if (transactionRepository.finishIfRequested(request.transactionId(), TransactionStatus.FAILED, timeProvider.now()) == 1) {
            paymentRepository.restoreBalance(request.paymentId(), request.amount());
        }
    }

    private PaymentResponse finishCancel(String mId, CancelRequest request) {
        transactionRepository.finishIfRequested(request.transactionId(), TransactionStatus.DONE, timeProvider.now());
        Payment payment = findOwned(mId, request.paymentKey());
        PaymentResponse response = responseOf(payment);
        if (request.idempotencyRecordId() != null) {
            idempotencyRecordRepository.saveResponse(request.idempotencyRecordId(), objectMapper.writeValueAsString(response));
        }
        metrics.cancel(PaymentMetrics.CANCELED);
        log.atInfo().setMessage("결제 취소")
                .addKeyValue("paymentKey", payment.getPaymentKey())
                .addKeyValue("transactionKey", request.transactionKey())
                .addKeyValue("cancelAmount", request.amount())
                .addKeyValue("balanceAmount", payment.getBalanceAmount())
                .log();
        return response;
    }

    public PaymentResponse replay(String mId, IdempotencyRecord record) {
        PaymentTransaction tx = transactionRepository.findById(record.getTransactionId())
                .orElseThrow(() -> new IllegalStateException("멱등 키에 연결된 거래가 없습니다. id=" + record.getTransactionId()));
        return switch (tx.getStatus()) {
            case DONE -> responseOf(findOwned(mId, record.getPaymentKey()));
            case REQUESTED -> throw new PgException(ErrorCode.CANCEL_IN_PROGRESS);
            case FAILED -> throw new PgException(ErrorCode.CANCEL_FAILED);
        };
    }

    private IdempotencyRecord findRecord(CancelPaymentCommand command) {
        if (command.idempotencyKey() == null) {
            return null;
        }
        return idempotencyRecordRepository.findByMerchantAndKey(command.mId(), command.idempotencyKey()).orElse(null);
    }

    private String fingerprintOf(CancelPaymentCommand command) {
        return Objects.requireNonNullElse(command.requestFingerprint(),
                command.paymentKey() + "|" + command.cancelAmount());
    }

    private PaymentResponse responseOf(Payment payment) {
        return PaymentResponse.from(payment, findCancels(payment.getPaymentKey()));
    }

    private record CancelRequest(Long paymentId, String paymentKey, Long transactionId, String transactionKey,
                                 String approveNo, long amount, Long idempotencyRecordId) {
    }

    public List<PaymentTransaction> findCancels(String paymentKey) {
        return transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL);
    }

    private Payment findLatest(Long paymentId) {
        return paymentRepository.findByIdForShare(paymentId)
                .orElseThrow(() -> new PgException(ErrorCode.NOT_FOUND_PAYMENT));
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
            case DONE, PARTIAL_CANCELED, CANCELED -> new PgException(ErrorCode.ALREADY_PROCESSED_PAYMENT);
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