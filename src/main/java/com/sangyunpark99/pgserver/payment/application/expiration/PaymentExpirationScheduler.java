package com.sangyunpark99.pgserver.payment.application.expiration;

import com.sangyunpark99.pgserver.common.time.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpirationScheduler {
    private final PaymentExpirationService expirationService;
    private final TimeProvider timeProvider;

    @Scheduled(fixedDelayString = "${pg.expiration.fixed-delay:60000}")
    public void expire() {
        long start = System.currentTimeMillis();
        LocalDateTime now = timeProvider.now();
        int total = 0;
        int count;
        do {
            count = expirationService.expireOverdue(now);
            total += count;
        } while (count > 0);
        if (total > 0) {
            log.info("결제 만료 처리: {}건, {}ms", total, System.currentTimeMillis() - start);
        }
    }
}
