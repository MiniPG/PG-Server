package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;

import java.time.LocalDateTime;

public record TransactionResponse(
        String transactionKey,
        String paymentKey,
        String mId,
        TransactionType type,
        long amount,
        TransactionStatus status,
        String reason,
        LocalDateTime transactedAt,
        LocalDateTime completedAt
) {
    public static TransactionResponse from(PaymentTransaction tx) {
        return new TransactionResponse(
                tx.getTransactionKey(), tx.getPaymentKey(), tx.getMId(), tx.getType(),
                tx.getAmount(), tx.getStatus(), tx.getReason(), tx.getTransactedAt(), tx.getCompletedAt()
        );
    }
}
