package com.sangyunpark99.pgserver.common.logging;

import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.sangyunpark99.pgserver.common.logging.LogEvents.BOOT_LOGSTASH;
import static com.sangyunpark99.pgserver.common.logging.LogEvents.OURS;
import static com.sangyunpark99.pgserver.common.logging.LogEvents.encoder;
import static org.assertj.core.api.Assertions.assertThat;

class LogstashJsonFormatterTest {

    @Test
    @DisplayName("결제 승인 로그를 Boot logstash 포맷과 같은 바이트로 만든다")
    void sameAsBootForApproval() {
        assertSame(LogEvents.approval());
    }

    @Test
    @DisplayName("이스케이프할 글자, 이모지, 마커, 예외가 있어도 Boot logstash 포맷과 같다")
    void sameAsBootForTricky() {
        assertSame(LogEvents.tricky());
    }

    private void assertSame(LoggingEvent event) {
        byte[] boot = encoder(BOOT_LOGSTASH).encode(event);
        byte[] ours = encoder(OURS).encode(event);
        assertThat(new String(ours, StandardCharsets.UTF_8)).isEqualTo(new String(boot, StandardCharsets.UTF_8));
        assertThat(ours).isEqualTo(boot);
    }
}
