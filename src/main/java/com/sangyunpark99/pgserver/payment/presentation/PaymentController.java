package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.application.CancelPaymentCommand;
import com.sangyunpark99.pgserver.payment.application.ConfirmPaymentCommand;
import com.sangyunpark99.pgserver.payment.application.CreatePaymentCommand;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {
    public static final String MERCHANT_HEADER = "X-Merchant-Id";

    private final PaymentService paymentService;

    @PostMapping
    public PaymentResponse create(@RequestHeader(MERCHANT_HEADER) String mId,
                                  @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.create(
                new CreatePaymentCommand(mId, request.orderId(), request.orderName(), request.amount(),
                        request.partialCancelable() == null || request.partialCancelable())
        );
        return toResponse(payment);
    }

    @PostMapping("/confirm")
    public PaymentResponse confirm(@RequestHeader(MERCHANT_HEADER) String mId,
                                   @RequestBody ConfirmPaymentRequest request) {
        Payment payment = paymentService.confirm(
                new ConfirmPaymentCommand(mId, request.paymentKey(), request.orderId(), request.amount())
        );
        return toResponse(payment);
    }

    @GetMapping("/{paymentKey}")
    public PaymentResponse getByPaymentKey(@RequestHeader(MERCHANT_HEADER) String mId,
                                           @PathVariable String paymentKey) {
        return toResponse(paymentService.getByPaymentKey(mId, paymentKey));
    }

    @GetMapping("/orders/{orderId}")
    public PaymentResponse getByOrderId(@RequestHeader(MERCHANT_HEADER) String mId,
                                        @PathVariable String orderId) {
        return toResponse(paymentService.getByOrderId(mId, orderId));
    }

    @PostMapping("/{paymentKey}/cancel")
    public PaymentResponse cancel(@RequestHeader(MERCHANT_HEADER) String mId,
                                  @PathVariable String paymentKey,
                                  @RequestBody CancelPaymentRequest request) {
        Payment payment = paymentService.cancel(
                new CancelPaymentCommand(mId, paymentKey, request.cancelReason(), request.cancelAmount())
        );
        return toResponse(payment);
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.from(payment, paymentService.findCancels(payment.getPaymentKey()));
    }
}
