package com.sangyunpark99.pgserver.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);

    private Payment newPayment() {
        return Payment.create("pk_test", "order-1", "티셔츠", "m_test", 15_000, true, NOW);
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
        assertThatThrownBy(() -> Payment.create("pk_test", "order-1", "티셔츠", "m_test", 0, true, NOW))
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

    private Payment approvedPayment(boolean partialCancelable) {
        Payment payment = Payment.create("pk_test", "order-1", "티셔츠", "m_test", 10_000, partialCancelable, NOW);
        payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW);
        return payment;
    }

    @Test
    @DisplayName("일부를 취소하면 잔액이 줄고 PARTIAL_CANCELED")
    void partialCancel() {
        Payment payment = approvedPayment(true);

        payment.cancel(3_000);

        assertThat(payment.getBalanceAmount()).isEqualTo(7_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
    }

    @Test
    @DisplayName("부분 취소를 여러 번 해서 잔액이 0이 되면 CANCELED")
    void partialCancel_untilZero() {
        Payment payment = approvedPayment(true);

        payment.cancel(3_000);
        payment.cancel(7_000);

        assertThat(payment.getBalanceAmount()).isZero();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("잔액 전체를 취소하면 CANCELED")
    void fullCancel() {
        Payment payment = approvedPayment(false);

        payment.cancel(10_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("잔액보다 큰 금액은 취소할 수 없다")
    void cancel_overBalance() {
        Payment payment = approvedPayment(true);
        payment.cancel(3_000);

        assertThatThrownBy(() -> payment.cancel(8_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("부분 취소 불가 결제는 잔액보다 적게 취소할 수 없다")
    void cancel_notPartialCancelable() {
        Payment payment = approvedPayment(false);

        assertThatThrownBy(() -> payment.cancel(3_000))
                .isInstanceOf(NotPartialCancelableException.class);
        assertThat(payment.getBalanceAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("승인 전(IN_PROGRESS) 결제는 취소할 수 없다")
    void cancel_beforeApprove() {
        Payment payment = newPayment();

        assertThatThrownBy(() -> payment.cancel(15_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CANCELED 결제는 다시 취소할 수 없다")
    void cancel_afterCanceled() {
        Payment payment = approvedPayment(true);
        payment.cancel(10_000);

        assertThatThrownBy(() -> payment.cancel(1_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("IN_PROGRESS 결제는 만료시킬 수 있다")
    void expire() {
        Payment payment = newPayment();

        payment.expire();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    @DisplayName("승인된 결제는 만료시킬 수 없다")
    void expire_afterApprove() {
        Payment payment = approvedPayment(true);

        assertThatThrownBy(payment::expire)
                .isInstanceOf(IllegalStateException.class);
    }
}
