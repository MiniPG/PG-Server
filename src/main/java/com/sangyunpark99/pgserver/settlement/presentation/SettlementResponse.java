package com.sangyunpark99.pgserver.settlement.presentation;

import com.sangyunpark99.pgserver.settlement.domain.Settlement;

import java.time.LocalDate;

public record SettlementResponse(
        String mId,
        LocalDate settlementDate,
        long approveAmount,
        long approveCount,
        long cancelAmount,
        long cancelCount,
        long netAmount,
        long fee,
        long payoutAmount
) {
    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(
                s.getMId(), s.getSettlementDate(),
                s.getApproveAmount(), s.getApproveCount(),
                s.getCancelAmount(), s.getCancelCount(),
                s.getNetAmount(), s.getFee(), s.getPayoutAmount()
        );
    }
}
