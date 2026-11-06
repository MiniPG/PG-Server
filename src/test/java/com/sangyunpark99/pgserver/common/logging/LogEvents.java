package com.sangyunpark99.pgserver.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.slf4j.MarkerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.Map;

public final class LogEvents {

    public static final String BOOT_LOGSTASH = "logstash";
    public static final String OURS = LogstashJsonFormatter.class.getName();

    private static final LoggerContext CONTEXT = new LoggerContext();

    static {
        CONTEXT.putObject(Environment.class.getName(), new MockEnvironment());
    }

    private LogEvents() {
    }

    public static StructuredLogEncoder encoder(String format) {
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(CONTEXT);
        encoder.setFormat(format);
        encoder.start();
        return encoder;
    }

    public static LoggingEvent approval() {
        LoggingEvent event = new LoggingEvent("fqcn",
                CONTEXT.getLogger("com.sangyunpark99.pgserver.payment.application.PaymentService"),
                Level.INFO, "결제 승인", null, null);
        event.setThreadName("http-nio-8080-exec-12");
        event.setInstant(Instant.parse("2026-11-06T06:01:02.123456Z"));
        event.setMDCPropertyMap(Map.of("requestId", "9f1c2b7e4d5a4c3b8e6f0a1b2c3d4e5f", "mId", "m_loadtest_0001"));
        event.addKeyValuePair(new KeyValuePair("paymentKey", "pk_7Hc2kQ9xLmN4pR8sT1vW3yZ5"));
        event.addKeyValuePair(new KeyValuePair("orderId", "order-20261106-000123456"));
        event.addKeyValuePair(new KeyValuePair("amount", 15000L));
        return event;
    }

    public static LoggingEvent tricky() {
        LoggingEvent event = new LoggingEvent("fqcn", CONTEXT.getLogger("tricky"), Level.WARN,
                "따옴표\" 역슬래시\\ 줄바꿈\n탭\t제어\u0001 이모지😀 끝", new IllegalStateException("원인 \"메시지\""),
                null);
        event.setThreadName("scheduling-1");
        event.setMDCPropertyMap(Map.of());
        event.setInstant(Instant.parse("2026-11-06T06:01:02Z"));
        event.addMarker(MarkerFactory.getMarker("AUDIT"));
        event.addKeyValuePair(new KeyValuePair("note", "값에도\n줄바꿈"));
        return event;
    }
}
