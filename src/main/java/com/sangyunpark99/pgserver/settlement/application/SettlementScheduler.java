package com.sangyunpark99.pgserver.settlement.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final SettlementService settlementService;

    @Scheduled(cron = "${pg.settlement.cron:0 0 2 * * *}", zone = "Asia/Seoul")
    public void settleYesterday() {
        LocalDate target = LocalDate.now(ZONE).minusDays(1);
        long start = System.currentTimeMillis();
        int count = settlementService.settle(target);
        log.info("정산 완료: {} 가맹점 {}곳, {}ms", target, count, System.currentTimeMillis() - start);
    }
}
