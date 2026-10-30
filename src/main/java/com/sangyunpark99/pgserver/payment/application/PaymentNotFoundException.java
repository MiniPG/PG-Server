package com.sangyunpark99.pgserver.payment.application;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException() {
        super("존재하지 않는 결제입니다.");
    }
}
