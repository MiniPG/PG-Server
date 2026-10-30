package com.sangyunpark99.pgserver.payment.application;

public record CancelPaymentCommand(
        String mId,
        String paymentKey,
        String cancelReason,
        Long cancelAmount
) {
}
