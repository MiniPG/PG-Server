package com.sangyunpark99.pgserver.settlement.application;

import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import com.sangyunpark99.pgserver.settlement.domain.Settlement;
import com.sangyunpark99.pgserver.settlement.infrastructure.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SettlementServiceTest {

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    private final LocalDate day = LocalDate.of(2026, 10, 30);

    @BeforeEach
    void setUp() {
        settlementRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    @Test
    @DisplayName("가맹점별로 그날 확정된 승인·취소를 집계한다")
    void settle() {
        done("m_a", TransactionType.APPROVE, 10_000, day.atTime(10, 0));
        done("m_a", TransactionType.APPROVE, 20_000, day.atTime(11, 0));
        done("m_a", TransactionType.CANCEL, 5_000, day.atTime(12, 0));
        done("m_b", TransactionType.APPROVE, 7_000, day.atTime(13, 0));

        int count = settlementService.settle(day);

        assertThat(count).isEqualTo(2);
        Settlement a = settlementOf("m_a");
        assertThat(a.getApproveAmount()).isEqualTo(30_000);
        assertThat(a.getApproveCount()).isEqualTo(2);
        assertThat(a.getCancelAmount()).isEqualTo(5_000);
        assertThat(a.getCancelCount()).isEqualTo(1);
        assertThat(a.getNetAmount()).isEqualTo(25_000);
        assertThat(a.getFee()).isEqualTo(750);
        assertThat(a.getPayoutAmount()).isEqualTo(24_250);
        assertThat(settlementOf("m_b").getApproveAmount()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("FAILED, REQUESTED 거래는 돈이 움직이지 않았거나 모르는 상태라 제외한다")
    void onlyDone() {
        done("m_a", TransactionType.APPROVE, 10_000, day.atTime(10, 0));
        failed("m_a", TransactionType.APPROVE, 50_000, day.atTime(10, 5));
        requested("m_a", TransactionType.APPROVE, 70_000, day.atTime(10, 10));

        settlementService.settle(day);

        assertThat(settlementOf("m_a").getApproveAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("요청 시각이 아니라 확정 시각 기준: 23:59:59에 요청, 00:00:00.3에 확정되면 다음 날 정산")
    void byCompletedAt() {
        PaymentTransaction tx = request("m_a", TransactionType.APPROVE, 10_000, day.atTime(23, 59, 59));
        tx.complete(day.plusDays(1).atTime(0, 0, 0, 300_000_000));
        transactionRepository.save(tx);

        assertThat(settlementService.settle(day)).isZero();
        assertThat(settlementService.settle(day.plusDays(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("전날 승인 건을 오늘 취소하면 오늘 정산에서 차감되고, 마이너스 지급액도 그대로 기록한다")
    void cancelOfPreviousDay() {
        done("m_a", TransactionType.APPROVE, 10_000, day.atTime(10, 0));
        done("m_a", TransactionType.CANCEL, 3_000, day.plusDays(1).atTime(9, 0));

        settlementService.settle(day);
        settlementService.settle(day.plusDays(1));

        assertThat(settlementOf("m_a", day).getPayoutAmount()).isEqualTo(9_700);
        Settlement next = settlementOf("m_a", day.plusDays(1));
        assertThat(next.getNetAmount()).isEqualTo(-3_000);
        assertThat(next.getFee()).isEqualTo(-90);
        assertThat(next.getPayoutAmount()).isEqualTo(-2_910);
    }

    @Test
    @DisplayName("같은 날짜로 다시 돌리면 이미 정산된 가맹점은 건너뛰고, 정산은 바뀌지 않는다")
    void rerun() {
        done("m_a", TransactionType.APPROVE, 10_000, day.atTime(10, 0));
        assertThat(settlementService.settle(day)).isEqualTo(1);

        done("m_a", TransactionType.APPROVE, 99_000, day.atTime(11, 0));
        done("m_b", TransactionType.APPROVE, 5_000, day.atTime(12, 0));

        assertThat(settlementService.settle(day)).isEqualTo(1);
        assertThat(settlementOf("m_a").getApproveAmount()).isEqualTo(10_000);
        assertThat(settlementOf("m_b").getApproveAmount()).isEqualTo(5_000);
    }

    private void done(String mId, TransactionType type, long amount, LocalDateTime completedAt) {
        PaymentTransaction tx = request(mId, type, amount, completedAt.minusSeconds(1));
        tx.complete(completedAt);
        transactionRepository.save(tx);
    }

    private void failed(String mId, TransactionType type, long amount, LocalDateTime completedAt) {
        PaymentTransaction tx = request(mId, type, amount, completedAt.minusSeconds(1));
        tx.fail(completedAt);
        transactionRepository.save(tx);
    }

    private void requested(String mId, TransactionType type, long amount, LocalDateTime transactedAt) {
        transactionRepository.save(request(mId, type, amount, transactedAt));
    }

    private PaymentTransaction request(String mId, TransactionType type, long amount, LocalDateTime transactedAt) {
        return PaymentTransaction.request("tx_" + UUID.randomUUID().toString().replace("-", ""),
                "pk_test", mId, type, amount, null, transactedAt);
    }

    private Settlement settlementOf(String mId) {
        return settlementOf(mId, day);
    }

    private Settlement settlementOf(String mId, LocalDate date) {
        return settlementRepository.findByMerchantAndPeriod(mId, date, date).getFirst();
    }
}
