package com.sangyunpark99.pgserver.common.exception;

import lombok.Getter;

@Getter
public class CardCancelFailedException extends PgException {
    private final String failureCode;

    public CardCancelFailedException(String failureCode, String failureMessage) {
        super(ErrorCode.CANCEL_FAILED, "[" + failureCode + "] " + failureMessage);
        this.failureCode = failureCode;
    }
}
