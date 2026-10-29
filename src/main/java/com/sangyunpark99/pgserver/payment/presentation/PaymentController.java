package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.application.CreatePaymentCommand;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    /** 가맹점 식별 헤더. 지금은 단순하게 mId만 받고, 시크릿 키 인증은 나중에 추가한다. */
    public static final String MERCHANT_HEADER = "X-Merchant-Id";

    private final PaymentService paymentService;

    @PostMapping
    public PaymentResponse create(@RequestHeader(MERCHANT_HEADER) String mId,
                                  @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.create(
                new CreatePaymentCommand(mId, request.orderId(), request.orderName(), request.amount())
        );
        return PaymentResponse.from(payment);
    }
}
