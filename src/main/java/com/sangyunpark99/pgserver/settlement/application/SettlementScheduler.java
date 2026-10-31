package com.sangyunpark99.pgserver.settlement.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
public class SettlementScheduler {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final SettlementService settlementService;
    private final MeterRegistry registry;
    private final Counter merchantsCounter;
    private final Timer successTimer;
    private final Timer failureTimer;

    public SettlementScheduler(SettlementService settlementService, MeterRegistry registry) {
        this.settlementService = settlementService;
        this.registry = registry;
        this.merchantsCounter = registry.counter("settlement.merchants");
        this.successTimer = registry.timer("settlement.duration", "result", "success");
        this.failureTimer = registry.timer("settlement.duration", "result", "failure");
    }

    @Scheduled(cron = "${pg.settlement.cron:0 0 2 * * *}", zone = "Asia/Seoul")
    public void settleYesterday() {
        LocalDate target = LocalDate.now(ZONE).minusDays(1);
        Timer.Sample sample = Timer.start(registry);
        try {
            int count = settlementService.settle(target);
            sample.stop(successTimer);
            merchantsCounter.increment(count);
            log.info("정산 완료: {} 가맹점 {}곳", target, count);
        } catch (RuntimeException e) {
            sample.stop(failureTimer);
            log.error("정산 실패: {}", target, e);
            throw e;
        }
    }
}
