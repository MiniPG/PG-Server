package com.sangyunpark99.pgserver.support;

import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.card.CardTransactionPage;
import com.sangyunpark99.pgserver.card.CardTransactionRecord;

import java.time.LocalDateTime;
import java.util.List;

import java.util.concurrent.atomic.AtomicInteger;

public class DelayedCardCompanyClient implements CardCompanyClient {

    private final long delayMillis;
    private final AtomicInteger approveCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();

    public DelayedCardCompanyClient(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    @Override
    public CardApprovalResult approve(String transactionKey, String paymentKey, long amount) {
        int n = approveCalls.incrementAndGet();
        sleep();
        return CardApprovalResult.approved(String.format("%08d", n), "11", "1234-****-****-5678");
    }

    @Override
    public CardCancelResult cancel(String transactionKey, String approveNo, long amount) {
        cancelCalls.incrementAndGet();
        sleep();
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

    public int approveCalls() {
        return approveCalls.get();
    }

    public int cancelCalls() {
        return cancelCalls.get();
    }

    public void reset() {
        approveCalls.set(0);
        cancelCalls.set(0);
    }

    private void sleep() {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
