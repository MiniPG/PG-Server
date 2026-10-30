package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;

import java.time.LocalDateTime;

public record PaymentResponse(
        String paymentKey,
        String orderId,
        String orderName,
        String mId,
        long totalAmount,
        long balanceAmount,
        PaymentStatus status,
        LocalDateTime requestedAt,
        LocalDateTime approvedAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getPaymentKey(), p.getOrderId(), p.getOrderName(), p.getMId(),
                p.getTotalAmount(), p.getBalanceAmount(), p.getStatus(),
                p.getRequestedAt(), p.getApprovedAt()
        );
    }
}
