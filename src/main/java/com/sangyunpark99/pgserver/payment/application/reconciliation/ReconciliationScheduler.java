package com.sangyunpark99.pgserver.payment.application.reconciliation;

import com.sangyunpark99.pgserver.common.time.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {
    private final ReconciliationService reconciliationService;
    private final TimeProvider timeProvider;

    @Scheduled(fixedDelayString = "${pg.reconciliation.fixed-delay:10000}",
            initialDelayString = "${pg.reconciliation.fixed-delay:10000}")
    public void reconcile() {
        long start = System.currentTimeMillis();
        int count = reconciliationService.reconcile(timeProvider.now());
        if (count > 0) {
            log.info("대사 처리: {}건, {}ms", count, System.currentTimeMillis() - start);
        }
    }
}
