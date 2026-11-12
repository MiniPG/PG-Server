package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.application.CancelPaymentCommand;
import com.sangyunpark99.pgserver.payment.application.ConfirmPaymentCommand;
import com.sangyunpark99.pgserver.payment.application.CreatePaymentCommand;
import com.sangyunpark99.pgserver.payment.application.PaymentResponse;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
import com.sangyunpark99.pgserver.common.idempotency.IdempotencyFilter;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.merchant.presentation.AuthenticatedMerchant;
import com.sangyunpark99.pgserver.merchant.presentation.CachedAuth;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @CachedAuth
    @PostMapping
    public PaymentResponse create(@AuthenticatedMerchant String mId,
                                  @Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.create(
                new CreatePaymentCommand(mId, request.orderId(), request.orderName(), request.amount(),
                        request.partialCancelable() == null || request.partialCancelable())
        );

        return PaymentResponse.from(payment, List.of());
    }

    @PostMapping("/confirm")
    public PaymentResponse confirm(@AuthenticatedMerchant String mId,
                                   @RequestHeader(value = IdempotencyFilter.HEADER, required = false) String idempotencyKey,
                                   @RequestAttribute(value = IdempotencyFilter.FINGERPRINT_ATTRIBUTE, required = false) String fingerprint,
                                   @RequestBody ConfirmPaymentRequest request) {
        return paymentService.confirm(new ConfirmPaymentCommand(
                mId, request.paymentKey(), request.orderId(), request.amount(), idempotencyKey, fingerprint));
    }

    @CachedAuth
    @GetMapping("/{paymentKey}")
    public PaymentResponse getByPaymentKey(@AuthenticatedMerchant String mId,
                                           @PathVariable String paymentKey) {
        return toResponse(paymentService.getByPaymentKey(mId, paymentKey));
    }

    @CachedAuth
    @GetMapping("/orders/{orderId}")
    public PaymentResponse getByOrderId(@AuthenticatedMerchant String mId,
                                        @PathVariable String orderId) {
        return toResponse(paymentService.getByOrderId(mId, orderId));
    }

    @PostMapping("/{paymentKey}/cancel")
    public PaymentResponse cancel(@AuthenticatedMerchant String mId,
                                  @PathVariable String paymentKey,
                                  @RequestHeader(value = IdempotencyFilter.HEADER, required = false) String idempotencyKey,
                                  @RequestAttribute(value = IdempotencyFilter.FINGERPRINT_ATTRIBUTE, required = false) String fingerprint,
                                  @RequestBody CancelPaymentRequest request) {
        return paymentService.cancel(new CancelPaymentCommand(
                mId, paymentKey, request.cancelReason(), request.cancelAmount(), idempotencyKey, fingerprint));
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.from(payment, paymentService.findCancels(payment.getPaymentKey()));
    }
}
