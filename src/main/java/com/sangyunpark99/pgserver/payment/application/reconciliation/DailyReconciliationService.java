package com.sangyunpark99.pgserver.payment.application.reconciliation;

import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.card.CardTransactionPage;
import com.sangyunpark99.pgserver.card.CardTransactionRecord;
import com.sangyunpark99.pgserver.common.time.TimeProvider;
import com.sangyunpark99.pgserver.payment.domain.MismatchType;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.ReconciliationMismatch;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.ReconciliationMismatchRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DailyReconciliationService {
    private final PaymentTransactionRepository transactionRepository;
    private final ReconciliationMismatchRepository mismatchRepository;
    private final CardCompanyClient cardCompanyClient;
    private final TransactionTemplate transactionTemplate;
    private final TimeProvider timeProvider;
    private final MeterRegistry registry;
    private final int pageSize;

    public DailyReconciliationService(PaymentTransactionRepository transactionRepository,
                                      ReconciliationMismatchRepository mismatchRepository,
                                      CardCompanyClient cardCompanyClient,
                                      TransactionTemplate transactionTemplate,
                                      TimeProvider timeProvider,
                                      MeterRegistry registry,
                                      @Value("${pg.reconciliation.daily.page-size:1000}") int pageSize) {
        this.transactionRepository = transactionRepository;
        this.mismatchRepository = mismatchRepository;
        this.cardCompanyClient = cardCompanyClient;
        this.transactionTemplate = transactionTemplate;
        this.timeProvider = timeProvider;
        this.registry = registry;
        this.pageSize = pageSize;
    }

    public DailyReconciliationResult reconcile(LocalDate businessDate) {
        LocalDateTime from = businessDate.atStartOfDay();
        LocalDateTime to = from.plusDays(1);
        List<ReconciliationMismatch> mismatches = new ArrayList<>();

        int pgCompared = comparePgSide(businessDate, from, to, mismatches);
        int cardCompared = compareCardSide(businessDate, from, to, mismatches);

        transactionTemplate.executeWithoutResult(status -> {
            mismatchRepository.deleteByBusinessDate(businessDate);
            mismatchRepository.saveAll(mismatches);
        });
        mismatches.forEach(m -> registry.counter("reconciliation.daily.mismatch", "type", m.getType().name()).increment());

        log.atInfo().setMessage("D+1 대사 완료")
                .addKeyValue("businessDate", businessDate)
                .addKeyValue("pgCompared", pgCompared)
                .addKeyValue("cardCompared", cardCompared)
                .addKeyValue("mismatches", mismatches.size())
                .log();
        return new DailyReconciliationResult(businessDate, pgCompared, cardCompared, mismatches.size());
    }

    private int comparePgSide(LocalDate businessDate, LocalDateTime from, LocalDateTime to,
                              List<ReconciliationMismatch> mismatches) {
        int compared = 0;
        PaymentTransaction last = null;
        while (true) {
            PageRequest page = PageRequest.of(0, pageSize);
            List<PaymentTransaction> pgPage = last == null
                    ? transactionRepository.findReconciliationFirstPage(from, to, page)
                    : transactionRepository.findReconciliationPageAfter(from, to, last.getTransactedAt(), last.getId(), page);
            if (pgPage.isEmpty()) {
                return compared;
            }

            List<String> keys = pgPage.stream().map(PaymentTransaction::getTransactionKey).toList();
            Map<String, CardTransactionRecord> cardByKey = cardCompanyClient.lookup(keys).stream()
                    .collect(Collectors.toMap(CardTransactionRecord::transactionKey, Function.identity()));
            for (PaymentTransaction pg : pgPage) {
                CardTransactionRecord card = cardByKey.get(pg.getTransactionKey());
                judge(pg, card).ifPresent(type -> mismatches.add(ReconciliationMismatch.of(
                        businessDate, pg.getTransactionKey(), type,
                        pg.getStatus().name(), card == null ? null : card.status(),
                        pg.getAmount(), card == null ? null : card.amount(), timeProvider.now())));
            }

            compared += pgPage.size();
            if (pgPage.size() < pageSize) {
                return compared;
            }
            last = pgPage.getLast();
        }
    }

    private int compareCardSide(LocalDate businessDate, LocalDateTime from, LocalDateTime to,
                                List<ReconciliationMismatch> mismatches) {
        int compared = 0;
        String startingAfter = null;
        while (true) {
            CardTransactionPage cardPage = cardCompanyClient.listTransactions(from, to, startingAfter, pageSize);
            List<CardTransactionRecord> records = cardPage.transactions();
            if (records.isEmpty()) {
                return compared;
            }

            Set<String> pgKeys = transactionRepository.findByTransactionKeyIn(
                            records.stream().map(CardTransactionRecord::transactionKey).toList())
                    .stream().map(PaymentTransaction::getTransactionKey).collect(Collectors.toSet());
            for (CardTransactionRecord card : records) {
                if (!pgKeys.contains(card.transactionKey())) {
                    mismatches.add(ReconciliationMismatch.of(businessDate, card.transactionKey(),
                            MismatchType.MISSING_IN_PG, null, card.status(), null, card.amount(), timeProvider.now()));
                }
            }

            compared += records.size();
            if (!cardPage.hasMore()) {
                return compared;
            }
            startingAfter = records.getLast().transactionKey();
        }
    }

    private Optional<MismatchType> judge(PaymentTransaction pg, CardTransactionRecord card) {
        if (pg.getStatus() == TransactionStatus.REQUESTED) {
            return Optional.of(MismatchType.UNRESOLVED);
        }
        if (card == null) {
            return Optional.of(MismatchType.MISSING_IN_CARD);
        }
        if (!pg.getType().name().equals(card.type()) || !expectedCardStatuses(pg).contains(card.status())) {
            return Optional.of(MismatchType.STATUS_MISMATCH);
        }
        if (pg.getAmount() != card.amount()) {
            return Optional.of(MismatchType.AMOUNT_MISMATCH);
        }
        return Optional.empty();
    }

    private Set<String> expectedCardStatuses(PaymentTransaction pg) {
        if (pg.getStatus() == TransactionStatus.DONE) {
            return Set.of("APPROVED");
        }
        return pg.getType() == TransactionType.APPROVE ? Set.of("REJECTED", "REVERSED") : Set.of("REJECTED");
    }
}
