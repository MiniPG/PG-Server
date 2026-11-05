package com.sangyunpark99.pgserver.payment.application.expiration;

import com.sangyunpark99.pgserver.payment.domain.CardInfo;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentExpirationServiceTest {

    @Autowired
    private PaymentExpirationService expirationService;

    @Autowired
    private PaymentRepository paymentRepository;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 30, 12, 0);

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
    }

    @Test
    @DisplayName("30분이 지난 IN_PROGRESS 결제만 EXPIRED로 바꾸고, 승인 처리 중(APPROVING)인 결제는 건드리지 않는다")
    void expireOverdue() {
        String overdue = save(now.minusMinutes(31));
        String justBefore = save(now.minusMinutes(29));
        String approving = saveApproving(now.minusMinutes(31));
        String approved = saveApproved(now.minusMinutes(31));

        int count = expirationService.expireOverdue(now);

        assertThat(count).isEqualTo(1);
        assertThat(statusOf(overdue)).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(statusOf(justBefore)).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(statusOf(approving)).isEqualTo(PaymentStatus.APPROVING);
        assertThat(statusOf(approved)).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("딱 30분인 결제는 아직 만료하지 않는다")
    void boundary() {
        String exactly30 = save(now.minusMinutes(30));

        expirationService.expireOverdue(now);

        assertThat(statusOf(exactly30)).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("이미 만료된 결제는 다시 처리하지 않는다")
    void idempotent() {
        save(now.minusMinutes(31));

        assertThat(expirationService.expireOverdue(now)).isEqualTo(1);
        assertThat(expirationService.expireOverdue(now)).isZero();
    }

    @Test
    @DisplayName("만료 대상이 배치 크기보다 많으면 한 번에 배치 크기만큼만 만료하고, 다음 호출에서 나머지를 만료한다")
    void batch() {
        for (int i = 0; i < PaymentExpirationService.BATCH_SIZE + 1; i++) {
            save(now.minusMinutes(31));
        }

        assertThat(expirationService.expireOverdue(now)).isEqualTo(PaymentExpirationService.BATCH_SIZE);
        assertThat(expirationService.expireOverdue(now)).isEqualTo(1);
        assertThat(expirationService.expireOverdue(now)).isZero();
    }

    private String save(LocalDateTime requestedAt) {
        String paymentKey = "pk_" + UUID.randomUUID().toString().replace("-", "");
        paymentRepository.save(Payment.create(paymentKey, "order-" + UUID.randomUUID(), "티셔츠",
                "m_test", 10_000, true, requestedAt));
        return paymentKey;
    }

    private String saveApproving(LocalDateTime requestedAt) {
        String paymentKey = "pk_" + UUID.randomUUID().toString().replace("-", "");
        Payment payment = Payment.create(paymentKey, "order-" + UUID.randomUUID(), "티셔츠",
                "m_test", 10_000, true, requestedAt);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.APPROVING);
        paymentRepository.save(payment);
        return paymentKey;
    }

    private String saveApproved(LocalDateTime requestedAt) {
        String paymentKey = "pk_" + UUID.randomUUID().toString().replace("-", "");
        Payment payment = Payment.create(paymentKey, "order-" + UUID.randomUUID(), "티셔츠",
                "m_test", 10_000, true, requestedAt);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.APPROVING);
        payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), requestedAt.plusSeconds(1));
        paymentRepository.save(payment);
        return paymentKey;
    }

    private PaymentStatus statusOf(String paymentKey) {
        return paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getStatus();
    }
}
