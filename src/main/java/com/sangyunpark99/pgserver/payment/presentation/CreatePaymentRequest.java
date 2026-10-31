package com.sangyunpark99.pgserver.payment.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 요청 형식(필수 값, 길이)은 여기서 검증한다. 금액(amount ≤ 0)은 결제 규칙이므로 Payment.create()가 검증한다.
public record CreatePaymentRequest(
        @NotBlank(message = "orderId는 필수입니다.")
        @Size(max = 64, message = "orderId는 64자 이하여야 합니다.")
        String orderId,

        @Size(max = 100, message = "orderName은 100자 이하여야 합니다.")
        String orderName,

        long amount,
        Boolean partialCancelable
) {
}
