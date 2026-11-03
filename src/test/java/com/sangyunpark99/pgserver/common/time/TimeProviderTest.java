package com.sangyunpark99.pgserver.common.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TimeProviderTest {

    private final ZoneId zone = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("현재 시각을 마이크로초로 잘라서 자정 직전 시각이 다음 날로 넘어가지 않는다")
    void truncateToMicros() {
        LocalDateTime nanos = LocalDateTime.of(2026, 11, 1, 23, 59, 59, 999_999_600);
        TimeProvider timeProvider = new TimeProvider(Clock.fixed(nanos.atZone(zone).toInstant(), zone));

        assertThat(timeProvider.now()).isEqualTo(LocalDateTime.of(2026, 11, 1, 23, 59, 59, 999_999_000));
    }
}
