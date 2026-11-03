package com.sangyunpark99.pgserver.payment.application.expiration;

import com.sangyunpark99.pgserver.payment.application.PaymentMetrics;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PaymentExpirationService {
    private final PaymentRepository paymentRepository;
    private final PaymentMetrics metrics;

    @Transactional
    public int expireOverdue(LocalDateTime now) {
        int count = paymentRepository.expireOverdue(now.minus(Payment.VALID_DURATION));
        metrics.expired(count);
        return count;
    }
}
