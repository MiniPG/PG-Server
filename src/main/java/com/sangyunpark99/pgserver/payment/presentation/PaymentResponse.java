package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;

import java.time.LocalDateTime;
import java.util.List;

public record PaymentResponse(
        String paymentKey,
        String orderId,
        String orderName,
        String mId,
        long totalAmount,
        long balanceAmount,
        PaymentStatus status,
        LocalDateTime requestedAt,
        boolean partialCancelable,
        LocalDateTime approvedAt,
        List<CancelResponse> cancels
) {
    public static PaymentResponse from(Payment p, List<PaymentTransaction> cancelTransactions) {
        return new PaymentResponse(
                p.getPaymentKey(), p.getOrderId(), p.getOrderName(), p.getMId(),
                p.getTotalAmount(), p.getBalanceAmount(), p.getStatus(),
                p.getRequestedAt(), p.isPartialCancelable(), p.getApprovedAt(),
                cancelTransactions.stream().map(CancelResponse::from).toList()
        );
    }
}
