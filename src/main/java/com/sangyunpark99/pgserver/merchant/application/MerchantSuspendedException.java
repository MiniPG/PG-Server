package com.sangyunpark99.pgserver.merchant.application;

public class MerchantSuspendedException extends RuntimeException {

    public MerchantSuspendedException() {
        super("이용이 정지된 가맹점입니다.");
    }
}
