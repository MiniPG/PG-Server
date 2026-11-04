package com.sangyunpark99.pgserver.payment.application.reconciliation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReconciliationScheduler {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final DailyReconciliationService dailyReconciliationService;

    @Scheduled(cron = "${pg.reconciliation.daily.cron:0 0 1 * * *}", zone = "Asia/Seoul")
    public void reconcileYesterday() {
        LocalDate target = LocalDate.now(ZONE).minusDays(1);
        DailyReconciliationResult result = dailyReconciliationService.reconcile(target);
        if (result.mismatches() > 0) {
            log.warn("D+1 대사 불일치: {} {}건", target, result.mismatches());
        }
    }
}
