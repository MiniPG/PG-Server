package com.sangyunpark99.pgserver.payment.domain;

public enum MismatchType {
    UNRESOLVED,
    MISSING_IN_CARD,
    MISSING_IN_PG,
    STATUS_MISMATCH,
    AMOUNT_MISMATCH
}
