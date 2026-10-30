package com.sangyunpark99.pgserver.payment.application;

public record ConfirmPaymentCommand(
        String mId,
        String paymentKey,
        String orderId,
        long amount
) {
}
