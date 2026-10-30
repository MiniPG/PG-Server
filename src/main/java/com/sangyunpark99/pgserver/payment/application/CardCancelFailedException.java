package com.sangyunpark99.pgserver.payment.application;

import lombok.Getter;

@Getter
public class CardCancelFailedException extends RuntimeException {
    private final String failureCode;

    public CardCancelFailedException(String failureCode, String failureMessage) {
        super(failureMessage);
        this.failureCode = failureCode;
    }
}
