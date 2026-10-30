package com.sangyunpark99.pgserver.card;

/**
 * 카드사에 승인을 요청하는 창구.
 * 지금은 PG 안의 임시 구현(StubCardCompanyClient)을 쓰고,
 * 가짜 카드사 서버를 만들면 HTTP로 호출하는 구현으로 바꾼다.
 */
public interface CardCompanyClient {

    CardApprovalResult approve(String paymentKey, long amount);
}
