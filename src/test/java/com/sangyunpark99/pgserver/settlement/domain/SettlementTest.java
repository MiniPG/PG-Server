package com.sangyunpark99.pgserver.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementTest {

    private static final BigDecimal RATE = new BigDecimal("0.03");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 30);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 4, 0);

    @Test
    @DisplayName("순매출 = 승인 − 취소, 수수료 = 순매출 × 3% (원 단위 버림), 지급액 = 순매출 − 수수료")
    void create() {
        Settlement s = Settlement.create("m_test", DATE, 11_234, 10_000, 2, 1, RATE, NOW);

        assertThat(s.getNetAmount()).isEqualTo(1_234);
        assertThat(s.getFee()).isEqualTo(37);
        assertThat(s.getPayoutAmount()).isEqualTo(1_197);
    }

    @Test
    @DisplayName("순매출이 마이너스면 수수료도 마이너스(환급)이고 0 쪽으로 버린다")
    void negative() {
        Settlement s = Settlement.create("m_test", DATE, 0, 1_234, 0, 1, RATE, NOW);

        assertThat(s.getNetAmount()).isEqualTo(-1_234);
        assertThat(s.getFee()).isEqualTo(-37);
        assertThat(s.getPayoutAmount()).isEqualTo(-1_197);
    }

    @Test
    @DisplayName("승인과 취소가 같으면 지급액 0")
    void zero() {
        Settlement s = Settlement.create("m_test", DATE, 10_000, 10_000, 1, 1, RATE, NOW);

        assertThat(s.getFee()).isZero();
        assertThat(s.getPayoutAmount()).isZero();
    }
}
