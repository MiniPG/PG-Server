package com.sangyunpark99.pgserver.payment.application.reconciliation;

import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.card.CardTransactionPage;
import com.sangyunpark99.pgserver.card.CardTransactionRecord;
import com.sangyunpark99.pgserver.payment.domain.MismatchType;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.ReconciliationMismatch;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.ReconciliationMismatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@TestPropertySource(properties = "pg.reconciliation.daily.page-size=2")
class DailyReconciliationServiceTest {

    @Autowired
    private DailyReconciliationService dailyReconciliationService;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private ReconciliationMismatchRepository mismatchRepository;

    @MockitoBean
    private CardCompanyClient cardCompanyClient;

    private final Map<String, CardTransactionRecord> cardByKey = new LinkedHashMap<>();
    private final Map<String, LocalDateTime> cardCreatedAt = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        cardByKey.clear();
        cardCreatedAt.clear();
        given(cardCompanyClient.lookup(anyList())).willAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            return keys.stream().map(cardByKey::get).filter(Objects::nonNull).toList();
        });
        given(cardCompanyClient.listTransactions(any(), any(), any(), anyInt())).willAnswer(invocation -> {
            LocalDateTime from = invocation.getArgument(0);
            LocalDateTime to = invocation.getArgument(1);
            String startingAfter = invocation.getArgument(2);
            int limit = invocation.getArgument(3);
            List<CardTransactionRecord> inRange = cardCreatedAt.entrySet().stream()
                    .filter(e -> !e.getValue().isBefore(from) && e.getValue().isBefore(to))
                    .map(e -> cardByKey.get(e.getKey()))
                    .toList();
            int start = 0;
            if (startingAfter != null) {
                for (int i = 0; i < inRange.size(); i++) {
                    if (inRange.get(i).transactionKey().equals(startingAfter)) {
                        start = i + 1;
                    }
                }
            }
            int end = Math.min(start + limit, inRange.size());
            return new CardTransactionPage(new ArrayList<>(inRange.subList(start, end)), end < inRange.size());
        });
    }

    @Test
    @DisplayName("상태와 금액이 모두 맞으면 불일치가 없다")
    void allMatched() {
        LocalDate day = LocalDate.of(2020, 2, 1);
        LocalDateTime at = day.atTime(10, 0);
        matched(TransactionType.APPROVE, TransactionStatus.DONE, "APPROVED", at);
        matched(TransactionType.APPROVE, TransactionStatus.FAILED, "REJECTED", at);
        matched(TransactionType.APPROVE, TransactionStatus.FAILED, "REVERSED", at);
        matched(TransactionType.CANCEL, TransactionStatus.DONE, "APPROVED", at);
        matched(TransactionType.CANCEL, TransactionStatus.FAILED, "REJECTED", at);

        DailyReconciliationResult result = dailyReconciliationService.reconcile(day);

        assertThat(mismatchTypes(day)).isEmpty();
        assertThat(result.pgCompared()).isEqualTo(5);
        assertThat(result.cardCompared()).isEqualTo(5);
    }

    @Test
    @DisplayName("PG는 승인 실패인데 카드사는 승인이면 상태 불일치")
    void statusMismatch() {
        LocalDate day = LocalDate.of(2020, 2, 2);
        String key = pg(TransactionType.APPROVE, TransactionStatus.FAILED, 10_000, day.atTime(10, 0));
        card(key, "APPROVE", 10_000, "APPROVED", day.atTime(10, 0));

        dailyReconciliationService.reconcile(day);

        List<ReconciliationMismatch> mismatches = mismatchRepository.findByBusinessDateOrderByIdAsc(day);
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.getFirst().getType()).isEqualTo(MismatchType.STATUS_MISMATCH);
        assertThat(mismatches.getFirst().getTransactionKey()).isEqualTo(key);
        assertThat(mismatches.getFirst().getPgStatus()).isEqualTo("FAILED");
        assertThat(mismatches.getFirst().getCardStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("금액이 다르면 금액 불일치")
    void amountMismatch() {
        LocalDate day = LocalDate.of(2020, 2, 3);
        String key = pg(TransactionType.APPROVE, TransactionStatus.DONE, 10_000, day.atTime(10, 0));
        card(key, "APPROVE", 9_000, "APPROVED", day.atTime(10, 0));

        dailyReconciliationService.reconcile(day);

        assertThat(mismatchTypes(day)).containsExactly(MismatchType.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("PG에만 있는 거래는 카드사에 없음")
    void missingInCard() {
        LocalDate day = LocalDate.of(2020, 2, 4);
        pg(TransactionType.CANCEL, TransactionStatus.DONE, 3_000, day.atTime(10, 0));

        dailyReconciliationService.reconcile(day);

        assertThat(mismatchTypes(day)).containsExactly(MismatchType.MISSING_IN_CARD);
    }

    @Test
    @DisplayName("카드사에만 있는 거래는 PG에 없음")
    void missingInPg() {
        LocalDate day = LocalDate.of(2020, 2, 5);
        card(newKey(), "APPROVE", 10_000, "APPROVED", day.atTime(10, 0));

        dailyReconciliationService.reconcile(day);

        assertThat(mismatchTypes(day)).containsExactly(MismatchType.MISSING_IN_PG);
    }

    @Test
    @DisplayName("다음 날까지 REQUESTED로 남은 거래는 미확정")
    void unresolved() {
        LocalDate day = LocalDate.of(2020, 2, 6);
        String key = pg(TransactionType.APPROVE, TransactionStatus.REQUESTED, 10_000, day.atTime(10, 0));
        card(key, "APPROVE", 10_000, "APPROVED", day.atTime(10, 0));

        dailyReconciliationService.reconcile(day);

        assertThat(mismatchTypes(day)).containsExactly(MismatchType.UNRESOLVED);
    }

    @Test
    @DisplayName("PG는 자정 직전, 카드사는 다음 날 0시에 기록된 거래도 양쪽 날짜 모두 불일치가 아니다")
    void midnightBoundary() {
        LocalDate day = LocalDate.of(2020, 2, 7);
        String key = pg(TransactionType.APPROVE, TransactionStatus.DONE, 10_000, day.atTime(23, 59, 59, 900_000_000));
        card(key, "APPROVE", 10_000, "APPROVED", day.plusDays(1).atTime(0, 0, 0, 100_000_000));

        dailyReconciliationService.reconcile(day);
        dailyReconciliationService.reconcile(day.plusDays(1));

        assertThat(mismatchTypes(day)).isEmpty();
        assertThat(mismatchTypes(day.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("같은 날짜를 다시 대사해도 불일치 기록이 쌓이지 않는다")
    void rerunSameDate() {
        LocalDate day = LocalDate.of(2020, 2, 8);
        String key = pg(TransactionType.APPROVE, TransactionStatus.FAILED, 10_000, day.atTime(10, 0));
        card(key, "APPROVE", 10_000, "APPROVED", day.atTime(10, 0));

        dailyReconciliationService.reconcile(day);
        dailyReconciliationService.reconcile(day);

        assertThat(mismatchTypes(day)).containsExactly(MismatchType.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("한 페이지보다 거래가 많아도 끝까지 비교한다")
    void manyPages() {
        LocalDate day = LocalDate.of(2020, 2, 9);
        LocalDateTime at = day.atTime(10, 0);
        for (int i = 0; i < 5; i++) {
            matched(TransactionType.APPROVE, TransactionStatus.DONE, "APPROVED", at.plusSeconds(i));
        }
        pg(TransactionType.APPROVE, TransactionStatus.DONE, 10_000, at.plusSeconds(10));

        DailyReconciliationResult result = dailyReconciliationService.reconcile(day);

        assertThat(mismatchTypes(day)).containsExactly(MismatchType.MISSING_IN_CARD);
        assertThat(result.pgCompared()).isEqualTo(6);
        assertThat(result.cardCompared()).isEqualTo(5);
    }

    private void matched(TransactionType type, TransactionStatus pgStatus, String cardStatus, LocalDateTime at) {
        String key = pg(type, pgStatus, 10_000, at);
        card(key, type.name(), 10_000, cardStatus, at);
    }

    private String pg(TransactionType type, TransactionStatus status, long amount, LocalDateTime at) {
        String key = newKey();
        PaymentTransaction tx = PaymentTransaction.request(key, "pk_daily", "m_daily", type, amount, null, at);
        if (status == TransactionStatus.DONE) {
            tx.complete(at);
        } else if (status == TransactionStatus.FAILED) {
            tx.fail(at);
        }
        transactionRepository.save(tx);
        return key;
    }

    private void card(String key, String type, long amount, String status, LocalDateTime createdAt) {
        cardByKey.put(key, new CardTransactionRecord(key, type, amount, status));
        cardCreatedAt.put(key, createdAt);
    }

    private List<MismatchType> mismatchTypes(LocalDate day) {
        return mismatchRepository.findByBusinessDateOrderByIdAsc(day).stream().map(ReconciliationMismatch::getType).toList();
    }

    private String newKey() {
        return "tx_" + UUID.randomUUID().toString().replace("-", "");
    }
}
