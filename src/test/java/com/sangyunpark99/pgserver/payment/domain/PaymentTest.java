package com.sangyunpark99.pgserver.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);

    private Payment newPayment() {
        return Payment.create("pk_test", "order-1", "티셔츠", "m_test", 15_000, NOW);
    }

    @Test
    @DisplayName("생성하면 IN_PROGRESS이고 잔액은 총액과 같다")
    void create() {
        Payment payment = newPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(payment.getBalanceAmount()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("금액이 0 이하면 생성할 수 없다")
    void create_invalidAmount() {
        assertThatThrownBy(() -> Payment.create("pk_test", "order-1", "티셔츠", "m_test", 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("승인하면 DONE이 되고 카드 정보와 승인 시각이 남는다")
    void approve() {
        Payment payment = newPayment();

        payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW.plusSeconds(1));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getCard().getApproveNo()).isEqualTo("12345678");
        assertThat(payment.getApprovedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("이미 DONE인 결제는 다시 승인할 수 없다")
    void approve_twice() {
        Payment payment = newPayment();
        payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW);

        assertThatThrownBy(() -> payment.approve(new CardInfo("11", "1234-****-****-5678", "87654321"), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("거절되면 ABORTED가 되고 실패 사유가 남는다")
    void abort() {
        Payment payment = newPayment();

        payment.abort("REJECT_CARD", "잔액 부족");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(payment.getFailureCode()).isEqualTo("REJECT_CARD");
    }

    @Test
    @DisplayName("ABORTED는 종료 상태라 승인으로 갈 수 없다")
    void aborted_isTerminal() {
        Payment payment = newPayment();
        payment.abort("REJECT_CARD", "잔액 부족");

        assertThatThrownBy(() -> payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("생성 후 30분이 지나야 만료로 본다")
    void isExpired() {
        Payment payment = newPayment();

        assertThat(payment.isExpired(NOW.plusMinutes(30))).isFalse();
        assertThat(payment.isExpired(NOW.plusMinutes(30).plusSeconds(1))).isTrue();
    }
}
