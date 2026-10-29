package com.sangyunpark99.pgserver.payment.application;

/** 결제 생성에 필요한 값. mId는 요청 본문이 아니라 가맹점 인증(헤더)에서 온다. */
public record CreatePaymentCommand(
        String mId,
        String orderId,
        String orderName,
        long amount
) {
}
