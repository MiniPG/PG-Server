package com.sangyunpark99.pgserver.payment.application.expiration;

import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentExpirationService {
    private final PaymentRepository paymentRepository;

    @Transactional
    public int expireOverdue(LocalDateTime now) {
        List<Payment> overdue = paymentRepository.findByStatusAndRequestedAtBefore(
                PaymentStatus.IN_PROGRESS, now.minus(Payment.VALID_DURATION)
        );
        overdue.forEach(Payment::expire);
        return overdue.size();
    }
}
