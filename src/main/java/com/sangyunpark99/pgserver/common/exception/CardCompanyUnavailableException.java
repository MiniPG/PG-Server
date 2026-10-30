package com.sangyunpark99.pgserver.common.exception;

public class CardCompanyUnavailableException extends PgException {

    public CardCompanyUnavailableException(String message, Throwable cause) {
        super(ErrorCode.CARD_COMPANY_UNAVAILABLE, message, cause);
    }
}
