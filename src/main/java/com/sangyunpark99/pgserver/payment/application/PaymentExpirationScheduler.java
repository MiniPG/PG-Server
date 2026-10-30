package com.sangyunpark99.pgserver.payment.application;

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

    @Scheduled(fixedDelayString = "${pg.expiration.fixed-delay:60000}")
    public void expire() {
        long start = System.currentTimeMillis();
        int count = expirationService.expireOverdue(LocalDateTime.now());
        if (count > 0) {
            log.info("결제 만료 처리: {}건, {}ms", count, System.currentTimeMillis() - start);
        }
    }
}
