package com.sangyunpark99.pgserver.payment.domain;

import java.util.Map;
import java.util.Set;

/**
 * 결제 상태.
 * 허용된 이동만 정의하고, 목록에 없는 이동은 모두 거절한다.
 *
 *   IN_PROGRESS → DONE      (카드사 승인 성공)
 *   IN_PROGRESS → ABORTED   (카드사 승인 거절)
 *   IN_PROGRESS → EXPIRED   (30분 동안 승인 요청 없음)
 *   DONE        → CANCELED  (승인된 결제 취소)
 *
 * ABORTED, EXPIRED, CANCELED는 끝 상태다.
 */
public enum PaymentStatus {
    IN_PROGRESS,
    DONE,
    ABORTED,
    EXPIRED,
    CANCELED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            IN_PROGRESS, Set.of(DONE, ABORTED, EXPIRED),
            DONE, Set.of(CANCELED),
            ABORTED, Set.of(),
            EXPIRED, Set.of(),
            CANCELED, Set.of()
    );

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
