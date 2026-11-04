package com.sangyunpark99.pgserver.card;

import java.time.LocalDateTime;
import java.util.List;

public interface CardCompanyClient {

    CardApprovalResult approve(String transactionKey, String paymentKey, long amount);

    CardCancelResult cancel(String transactionKey, String approveNo, long amount);

    void reverse(String transactionKey, String paymentKey, long amount);

    CardTransactionPage listTransactions(LocalDateTime from, LocalDateTime to, String startingAfter, int limit);

    List<CardTransactionRecord> lookup(List<String> transactionKeys);
}
