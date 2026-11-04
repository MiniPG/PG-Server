package com.sangyunpark99.pgserver.card;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "card.client", havingValue = "stub")
public class StubCardCompanyClient implements CardCompanyClient {
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public CardApprovalResult approve(String transactionKey, String paymentKey, long amount) {
        String approveNo = String.format("%08d", sequence.incrementAndGet() % 100_000_000);
        return CardApprovalResult.approved(approveNo, "11", "1234-****-****-5678");
    }

    @Override
    public CardCancelResult cancel(String transactionKey, String approveNo, long amount) {
        return CardCancelResult.success();
    }

    @Override
    public void reverse(String transactionKey, String paymentKey, long amount) {
    }

    @Override
    public CardTransactionPage listTransactions(LocalDateTime from, LocalDateTime to, String startingAfter, int limit) {
        return new CardTransactionPage(List.of(), false);
    }

    @Override
    public List<CardTransactionRecord> lookup(List<String> transactionKeys) {
        return List.of();
    }
}
