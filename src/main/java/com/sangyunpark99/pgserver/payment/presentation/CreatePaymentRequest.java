package com.sangyunpark99.pgserver.payment.presentation;

public record CreatePaymentRequest(
        String orderId,
        String orderName,
        long amount
) {
}
