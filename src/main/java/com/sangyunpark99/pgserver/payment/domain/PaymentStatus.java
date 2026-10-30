package com.sangyunpark99.pgserver.payment.domain;

import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    IN_PROGRESS,
    DONE,
    ABORTED,
    EXPIRED,
    PARTIAL_CANCELED,
    CANCELED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            IN_PROGRESS, Set.of(DONE, ABORTED, EXPIRED),
            DONE, Set.of(PARTIAL_CANCELED, CANCELED),
            PARTIAL_CANCELED, Set.of(PARTIAL_CANCELED, CANCELED),
            ABORTED, Set.of(),
            EXPIRED, Set.of(),
            CANCELED, Set.of()
    );

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
