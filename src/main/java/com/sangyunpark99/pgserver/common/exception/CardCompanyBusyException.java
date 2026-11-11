package com.sangyunpark99.pgserver.common.exception;

public class CardCompanyBusyException extends PgException {
    public CardCompanyBusyException() {
        super(ErrorCode.CARD_COMPANY_BUSY);
    }
}
