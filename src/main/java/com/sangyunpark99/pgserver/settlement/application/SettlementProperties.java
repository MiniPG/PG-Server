package com.sangyunpark99.pgserver.settlement.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "pg.settlement")
public record SettlementProperties(
        BigDecimal feeRate
) {
}
