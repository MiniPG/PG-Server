package com.sangyunpark99.pgserver.card;

public interface CardCompanyClient {

    CardApprovalResult approve(String transactionKey, String paymentKey, long amount);

    CardCancelResult cancel(String transactionKey, String approveNo, long amount);
}
