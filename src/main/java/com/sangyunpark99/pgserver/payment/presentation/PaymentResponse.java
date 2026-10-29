package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;

import java.time.LocalDateTime;

/** 가맹점에 돌려주는 결제 응답. 취소 내역(cancels)은 취소 API를 만들 때 PaymentTransaction에서 채운다. */
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
