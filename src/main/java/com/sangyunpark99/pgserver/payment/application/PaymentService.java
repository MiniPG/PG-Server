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

    /**
     * 결제 생성 (결제창 + 카드 인증을 대신한다).
     * - 요청에서 오는 값: mId(헤더), orderId, orderName, amount
     * - PG가 만드는 값: paymentKey, requestedAt, 상태(IN_PROGRESS)
     */
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

    /**
     * 결제 승인.
     * 1. paymentKey로 결제를 찾는다
     * 2. 요청한 가맹점, 주문 번호, 금액이 결제를 만들 때와 같은지 확인한다 (금액 조작 방지)
     * 3. 승인 거래를 REQUESTED로 기록한다
     * 4. 카드사에 승인을 요청한다
     * 5. 결과에 따라 결제는 DONE / ABORTED, 거래는 DONE / FAILED
     */
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
        // 카드사 호출 '전에' 상태를 확인해야 한다. 안 그러면 이미 DONE인 결제도 카드사까지 가버린다.
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