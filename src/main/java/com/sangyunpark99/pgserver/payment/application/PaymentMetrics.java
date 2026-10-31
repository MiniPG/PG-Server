package com.sangyunpark99.pgserver.payment.application;

import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentMetrics {
    public static final String APPROVED = "approved";
    public static final String CANCELED = "canceled";
    public static final String REJECTED = "rejected";
    public static final String UNAVAILABLE = "unavailable";

    private final Map<String, Counter> approveCounters;
    private final Map<String, Counter> cancelCounters;
    private final Counter expiredCounter;

    public PaymentMetrics(MeterRegistry registry, PaymentTransactionRepository transactionRepository) {
        this.approveCounters = counters(registry, "payment.approve", List.of(APPROVED, REJECTED, UNAVAILABLE));
        this.cancelCounters = counters(registry, "payment.cancel", List.of(CANCELED, REJECTED, UNAVAILABLE));
        this.expiredCounter = registry.counter("payment.expired");

        Gauge.builder("payment.transactions.requested", transactionRepository,
                        repo -> repo.countByStatus(TransactionStatus.REQUESTED))
                .description("카드사 결과를 아직 모르는 거래 수")
                .register(registry);
    }

    public void approve(String result) {
        increment(approveCounters, result);
    }

    public void cancel(String result) {
        increment(cancelCounters, result);
    }

    public void expired(int count) {
        expiredCounter.increment(count);
    }

    private static Map<String, Counter> counters(MeterRegistry registry, String name, List<String> results) {
        return results.stream().collect(Collectors.toUnmodifiableMap(
                Function.identity(),
                result -> registry.counter(name, "result", result)
        ));
    }

    private static void increment(Map<String, Counter> counters, String result) {
        Counter counter = counters.get(result);
        if (counter == null) {
            throw new IllegalArgumentException("등록되지 않은 결과 값입니다: " + result);
        }
        counter.increment();
    }
}
