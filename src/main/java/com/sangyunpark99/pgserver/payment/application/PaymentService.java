package com.sangyunpark99.pgserver.payment.application;

import com.sangyunpark99.pgserver.card.CardApprovalResult;
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
                LocalDateTime.now()
        );
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment confirm(ConfirmPaymentCommand command) {
        Payment payment = paymentRepository.findByPaymentKey(command.paymentKey())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 결제입니다. paymentKey=" + command.paymentKey()));

        verify(payment, command);

        PaymentTransaction tx = PaymentTransaction.request(
                generateTransactionKey(), payment.getPaymentKey(), TransactionType.APPROVE,
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

    private void verify(Payment payment, ConfirmPaymentCommand command) {
        if (!payment.getMId().equals(command.mId())) {
            throw new IllegalArgumentException("다른 가맹점의 결제입니다.");
        }
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