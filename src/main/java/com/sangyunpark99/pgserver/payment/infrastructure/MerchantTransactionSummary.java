package com.sangyunpark99.pgserver.payment.infrastructure;

public record MerchantTransactionSummary(
        String mId,
        Long approveAmount,
        Long cancelAmount,
        Long approveCount,
        Long cancelCount
) {
}
