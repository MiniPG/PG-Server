package com.sangyunpark99.pgserver.payment.presentation;

public record CancelPaymentRequest(
        String cancelReason,
        Long cancelAmount
) {
}
