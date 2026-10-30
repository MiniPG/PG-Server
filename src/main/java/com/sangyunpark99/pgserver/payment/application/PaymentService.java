package com.sangyunpark99.pgserver.payment.application;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final CardCompanyClient cardCompanyClient;

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
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment getByPaymentKey(String mId, String paymentKey) {
        return findOwned(mId, paymentKey);
    }

    @Transactional(readOnly = true)
    public Payment getByOrderId(String mId, String orderId) {
        return paymentRepository.findByMerchantAndOrderId(mId, orderId)
                .orElseThrow(PaymentNotFoundException::new);
    }

    @Transactional
    public Payment confirm(ConfirmPaymentCommand command) {
        Payment payment = findOwned(command.mId(), command.paymentKey());

        verify(payment, command);

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), payment.getMId(), TransactionType.APPROVE,
                payment.getTotalAmount(), null, LocalDateTime.now()
        );
        transactionRepository.save(tx);

        CardApprovalResult result = cardCompanyClient.approve(payment.getPaymentKey(), payment.getTotalAmount());

        if (result.approved()) {
            payment.approve(new CardInfo(result.issuerCode(), result.maskedNumber(), result.approveNo()), LocalDateTime.now());
            tx.complete();
        } else {
            payment.abort(result.failureCode(), result.failureMessage());
            tx.fail();
        }
        return payment;
    }

    @Transactional(noRollbackFor = CardCancelFailedException.class)
    public Payment cancel(CancelPaymentCommand command) {
        Payment payment = findOwned(command.mId(), command.paymentKey());

        if (command.cancelReason() == null || command.cancelReason().isBlank()) {
            throw new IllegalArgumentException("취소 사유는 필수입니다.");
        }
        long cancelAmount = command.cancelAmount() == null ? payment.getBalanceAmount() : command.cancelAmount();
        payment.validateCancel(cancelAmount);

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), payment.getMId(), TransactionType.CANCEL,
                cancelAmount, command.cancelReason(), LocalDateTime.now()
        );
        transactionRepository.save(tx);

        CardCancelResult result = cardCompanyClient.cancel(payment.getPaymentKey(), cancelAmount);

        if (!result.canceled()) {
            tx.fail();
            throw new CardCancelFailedException(result.failureCode(), result.failureMessage());
        }
        payment.cancel(cancelAmount);
        tx.complete();
        return payment;
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> findCancels(String paymentKey) {
        return transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL);
    }

    private Payment findOwned(String mId, String paymentKey) {
        return paymentRepository.findByPaymentKey(paymentKey)
                .filter(payment -> payment.getMId().equals(mId))
                .orElseThrow(PaymentNotFoundException::new);
    }

    private void verify(Payment payment, ConfirmPaymentCommand command) {
        if (!payment.getOrderId().equals(command.orderId())) {
            throw new IllegalArgumentException("주문 번호가 일치하지 않습니다.");
        }
        if (payment.getTotalAmount() != command.amount()) {
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다.");
        }

        if (payment.getStatus() != PaymentStatus.IN_PROGRESS) {
            throw new IllegalStateException("승인할 수 없는 상태입니다. status=" + payment.getStatus());
        }
        if (payment.isExpired(LocalDateTime.now())) {
            throw new IllegalStateException("결제 가능 시간(30분)이 지났습니다.");
        }
    }

    private String generateTransactionKey() {
        return "tx_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String generatePaymentKey() {
        return "pk_" + UUID.randomUUID().toString().replace("-", "");
    }
}