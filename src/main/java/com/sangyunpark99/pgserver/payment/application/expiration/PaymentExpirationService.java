package com.sangyunpark99.pgserver.payment.application.expiration;

import com.sangyunpark99.pgserver.payment.application.PaymentMetrics;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentExpirationService {
    private final PaymentRepository paymentRepository;
    private final PaymentMetrics metrics;

    public static final int BATCH_SIZE = 500;

    @Transactional
    public int expireOverdue(LocalDateTime now) {
        List<Long> ids = paymentRepository.findOverdueIds(now.minus(Payment.VALID_DURATION), Limit.of(BATCH_SIZE));
        if (ids.isEmpty()) {
            return 0;
        }
        int count = paymentRepository.expireIfInProgress(ids);
        metrics.expired(count);
        return count;
    }
}
