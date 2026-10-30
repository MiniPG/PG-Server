package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;

import java.time.LocalDateTime;

public record CancelResponse(
        String transactionKey,
        long cancelAmount,
        String cancelReason,
        TransactionStatus cancelStatus,
        LocalDateTime canceledAt
) {
    public static CancelResponse from(PaymentTransaction tx) {
        return new CancelResponse(
                tx.getTransactionKey(), tx.getAmount(), tx.getReason(), tx.getStatus(), tx.getTransactedAt()
        );
    }
}
