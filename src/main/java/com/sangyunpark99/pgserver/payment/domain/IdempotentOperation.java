package com.sangyunpark99.pgserver.payment.domain;

public enum IdempotentOperation {
    CREATE,
    CONFIRM,
    CANCEL
}
