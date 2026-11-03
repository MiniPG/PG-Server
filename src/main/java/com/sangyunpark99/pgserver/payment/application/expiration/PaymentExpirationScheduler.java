package com.sangyunpark99.pgserver.payment.application.expiration;

import com.sangyunpark99.pgserver.common.time.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpirationScheduler {
    private final PaymentExpirationService expirationService;
    private final TimeProvider timeProvider;

    @Scheduled(fixedDelayString = "${pg.expiration.fixed-delay:60000}")
    public void expire() {
        long start = System.currentTimeMillis();
        int count = expirationService.expireOverdue(timeProvider.now());
        if (count > 0) {
            log.info("결제 만료 처리: {}건, {}ms", count, System.currentTimeMillis() - start);
        }
    }
}
