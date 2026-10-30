package com.sangyunpark99.pgserver.card;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class StubCardCompanyClient implements CardCompanyClient {
    @Override
    public CardApprovalResult approve(String paymentKey, long amount) {
        String approveNo = String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        return CardApprovalResult.approved(approveNo, "11", "1234-****-****-5678");
    }

    @Override
    public CardCancelResult cancel(String paymentKey, long amount) {
        return CardCancelResult.success();
    }
}
