package com.sangyunpark99.pgserver.payment.domain;

import com.sangyunpark99.pgserver.common.exception.ErrorCode;
import com.sangyunpark99.pgserver.common.exception.PgException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);

    private Payment approving(Payment payment) {
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.APPROVING);
        return payment;
    }

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
                .isInstanceOf(PgException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("승인하면 DONE이 되고 카드 정보와 승인 시각이 남는다")
    void approve() {
        Payment payment = approving(newPayment());

        payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW.plusSeconds(1));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getCard().getApproveNo()).isEqualTo("12345678");
        assertThat(payment.getApprovedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("이미 DONE인 결제는 다시 승인할 수 없다")
    void approve_twice() {
        Payment payment = approving(newPayment());
        payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW);

        assertThatThrownBy(() -> payment.approve(new CardInfo("11", "1234-****-****-5678", "87654321"), NOW))
                .isInstanceOf(PgException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("거절되면 ABORTED가 되고 실패 사유가 남는다")
    void abort() {
        Payment payment = approving(newPayment());

        payment.abort("REJECT_CARD", "잔액 부족");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(payment.getFailureCode()).isEqualTo("REJECT_CARD");
    }

    @Test
    @DisplayName("ABORTED는 종료 상태라 승인으로 갈 수 없다")
    void aborted_isTerminal() {
        Payment payment = approving(newPayment());
        payment.abort("REJECT_CARD", "잔액 부족");

        assertThatThrownBy(() -> payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW))
                .isInstanceOf(PgException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("생성 후 30분이 지나야 만료로 본다")
    void isExpired() {
        Payment payment = newPayment();

        assertThat(payment.isExpired(NOW.plusMinutes(30))).isFalse();
        assertThat(payment.isExpired(NOW.plusMinutes(30).plusSeconds(1))).isTrue();
    }

    private Payment approvedPayment(boolean partialCancelable) {
        Payment payment = approving(Payment.create("pk_test", "order-1", "티셔츠", "m_test", 10_000, partialCancelable, NOW));
        payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW);
        return payment;
    }

    @Test
    @DisplayName("남은 금액보다 큰 금액은 취소할 수 없다")
    void validateCancel_overBalance() {
        Payment payment = approvedPayment(true);

        assertThatThrownBy(() -> payment.validateCancel(10_001))
                .isInstanceOf(PgException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXCEED_CANCEL_AMOUNT);
    }

    @Test
    @DisplayName("부분 취소 불가 결제는 남은 금액보다 적게 취소할 수 없다")
    void validateCancel_notPartialCancelable() {
        Payment payment = approvedPayment(false);

        assertThatThrownBy(() -> payment.validateCancel(3_000))
                .isInstanceOf(PgException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_PARTIAL_CANCELABLE);
    }

    @Test
    @DisplayName("승인 전(IN_PROGRESS) 결제는 취소할 수 없다")
    void validateCancel_beforeApprove() {
        Payment payment = newPayment();

        assertThatThrownBy(() -> payment.validateCancel(15_000))
                .isInstanceOf(PgException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("CANCELED 결제는 다시 취소할 수 없다")
    void validateCancel_afterCanceled() {
        Payment payment = approvedPayment(true);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.CANCELED);

        assertThatThrownBy(() -> payment.validateCancel(1_000))
                .isInstanceOf(PgException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("승인 처리 중(APPROVING)을 거치지 않으면 승인할 수 없다")
    void approve_withoutApproving() {
        Payment payment = newPayment();

        assertThatThrownBy(() -> payment.approve(new CardInfo("11", "1234-****-****-5678", "12345678"), NOW))
                .isInstanceOf(PgException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS);
    }
}
