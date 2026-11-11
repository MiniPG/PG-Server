package com.sangyunpark99.pgserver.card;

import com.sangyunpark99.pgserver.common.exception.CardCompanyBusyException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Component
public class CardCallBulkhead {
    private final Semaphore permits;
    private final Counter rejected;

    public CardCallBulkhead(@Value("${card.bulkhead.max-concurrent-calls:150}") int maxConcurrentCalls, MeterRegistry registry) {
        this.permits = new Semaphore(maxConcurrentCalls);
        this.rejected = registry.counter("card.bulkhead.rejected");
        Gauge.builder("card.bulkhead.available", permits, Semaphore::availablePermits).register(registry);
    }

    public <T> T execute(Supplier<T> call) {
        if(!permits.tryAcquire()) {
            rejected.increment();
            throw new CardCompanyBusyException();
        }

        try {
            return call.get();
        } finally {
            permits.release();
        }
    }
}
