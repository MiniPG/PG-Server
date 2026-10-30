package com.sangyunpark99.pgserver.card;

public interface CardCompanyClient {
    CardApprovalResult approve(String paymentKey, long amount);
}
