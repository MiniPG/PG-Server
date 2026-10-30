package com.sangyunpark99.pgserver.card;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** 임시 카드사: 항상 바로 승인한다. 가짜 카드사 서버(지연·거절·타임아웃 조절)로 교체 예정. */
@Component
public class StubCardCompanyClient implements CardCompanyClient {

    @Override
    public CardApprovalResult approve(String paymentKey, long amount) {
        String approveNo = String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        return CardApprovalResult.approved(approveNo, "11", "1234-****-****-5678");
    }
}
