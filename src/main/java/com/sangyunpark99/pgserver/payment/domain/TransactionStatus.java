package com.sangyunpark99.pgserver.payment.domain;

/** 카드사에 요청한 거래의 상태. 카드사 응답이 늦거나 실패할 수 있어서 요청과 완료를 나눈다. */
public enum TransactionStatus {
    REQUESTED,
    DONE,
    FAILED
}
