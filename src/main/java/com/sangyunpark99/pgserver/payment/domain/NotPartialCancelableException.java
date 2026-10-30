package com.sangyunpark99.pgserver.payment.domain;

public class NotPartialCancelableException extends IllegalArgumentException {

    public NotPartialCancelableException() {
        super("부분 취소가 불가능한 결제입니다.");
    }
}
