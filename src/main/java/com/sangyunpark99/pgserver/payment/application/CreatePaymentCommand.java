package com.sangyunpark99.pgserver.payment.application;

public record CreatePaymentCommand(
        String mId,
        String orderId,
        String orderName,
        long amount
) {
}
