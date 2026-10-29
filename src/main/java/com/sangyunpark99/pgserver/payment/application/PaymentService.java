package com.sangyunpark99.pgserver.payment.application;

import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

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

    private String generatePaymentKey() {
        return "pk_" + UUID.randomUUID().toString().replace("-", "");
    }
}
