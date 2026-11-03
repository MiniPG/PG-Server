package com.sangyunpark99.pgserver.payment.concurrency;

import com.sangyunpark99.pgserver.payment.application.PaymentMetrics;
import com.sangyunpark99.pgserver.payment.application.expiration.PaymentExpirationService;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class ExpirationApprovalRaceTest {

    @Autowired
    private PaymentExpirationService expirationService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @MockitoSpyBean
    private PaymentMetrics metrics;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Test
    @DisplayName("만료가 끝나기 전에 승인이 결제를 선점했다면, 만료가 그 결제를 EXPIRED로 덮어쓰지 않는다")
    void expireDoesNotOverwriteApproving() throws Exception {
        Payment payment = paymentRepository.save(Payment.create(
                "pk_" + UUID.randomUUID().toString().replace("-", ""), "order-" + UUID.randomUUID(), "티셔츠",
                "m_test", 10_000, true, now.minusMinutes(31)));

        AtomicReference<CompletableFuture<Integer>> claim = new AtomicReference<>();
        doAnswer(invocation -> {
            claim.set(CompletableFuture.supplyAsync(() -> transactionTemplate.execute(status ->
                    paymentRepository.changeStatusIf(payment.getId(), PaymentStatus.IN_PROGRESS, PaymentStatus.APPROVING))));
            try {
                claim.get().get(300, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
            }
            return invocation.callRealMethod();
        }).when(metrics).expired(anyInt());

        expirationService.expireOverdue(now);
        int claimed = claim.get().get(10, TimeUnit.SECONDS);
        PaymentStatus finalStatus = paymentRepository.findById(payment.getId()).orElseThrow().getStatus();

        System.out.printf("[만료 ↔ 승인] 승인 선점 결과 %d건, 최종 상태 %s%n", claimed, finalStatus);

        if (claimed == 1) {
            assertThat(finalStatus).isEqualTo(PaymentStatus.APPROVING);
        } else {
            assertThat(finalStatus).isEqualTo(PaymentStatus.EXPIRED);
        }
    }
}
