package com.sangyunpark99.pgserver.card;

public interface CardCompanyClient {

    CardApprovalResult approve(String paymentKey, long amount);

    CardCancelResult cancel(String paymentKey, long amount);
}
