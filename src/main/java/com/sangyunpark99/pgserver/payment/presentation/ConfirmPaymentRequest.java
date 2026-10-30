package com.sangyunpark99.pgserver.payment.presentation;

public record ConfirmPaymentRequest(
        String paymentKey,
        String orderId,
        long amount
) {
}
