package com.sangyunpark99.pgserver.payment.application.reconciliation;

import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.common.time.TimeProvider;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ReconciliationService {
    public static final Duration THRESHOLD = Duration.ofSeconds(10);
    private static final int BATCH_SIZE = 100;
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(60);

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final CardCompanyClient cardCompanyClient;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate claimTemplate;
    private final TimeProvider timeProvider;
    private final ExecutorService executor;

    public ReconciliationService(PaymentTransactionRepository transactionRepository,
                                 PaymentRepository paymentRepository,
                                 CardCompanyClient cardCompanyClient,
                                 TransactionTemplate transactionTemplate,
                                 TimeProvider timeProvider,
                                 @Value("${pg.reconciliation.concurrency:20}") int concurrency) {
        this.transactionRepository = transactionRepository;
        this.paymentRepository = paymentRepository;
        this.cardCompanyClient = cardCompanyClient;
        this.transactionTemplate = transactionTemplate;
        this.claimTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.claimTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.timeProvider = timeProvider;
        AtomicInteger sequence = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(concurrency,
                runnable -> new Thread(runnable, "reconciliation-" + sequence.incrementAndGet()));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public int reconcile(LocalDateTime now) {
        LocalDateTime deadline = now.minus(THRESHOLD);
        int resolved = 0;
        while (true) {
            List<PaymentTransaction> targets = claim(deadline, now);
            int batchResolved = resolveAll(targets);
            resolved += batchResolved;
            if (targets.size() < BATCH_SIZE || batchResolved == 0) {
                return resolved;
            }
        }
    }

    private List<PaymentTransaction> claim(LocalDateTime deadline, LocalDateTime now) {
        return claimTemplate.execute(status -> {
            List<Long> ids = transactionRepository.findClaimableIds(deadline, now, BATCH_SIZE);
            if (ids.isEmpty()) {
                return List.of();
            }
            transactionRepository.claim(ids, now.plus(CLAIM_LEASE));
            return transactionRepository.findByIdInOrderByIdAsc(ids);
        });
    }

    private void release(PaymentTransaction tx) {
        transactionTemplate.executeWithoutResult(status -> transactionRepository.releaseClaim(tx.getId()));
    }

    private int resolveAll(List<PaymentTransaction> targets) {
        List<Future<Boolean>> futures = targets.stream()
                .map(tx -> executor.submit(() -> resolve(tx)))
                .toList();
        int resolved = 0;
        for (Future<Boolean> future : futures) {
            if (await(future)) {
                resolved++;
            }
        }
        return resolved;
    }

    private boolean await(Future<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            log.atError().setMessage("대사 실패").setCause(e.getCause()).log();
            return false;
        }
    }

    private boolean resolve(PaymentTransaction tx) {
        try {
            return tx.getType() == TransactionType.APPROVE ? reverseApproval(tx) : retryCancel(tx);
        } catch (CardCompanyUnavailableException e) {
            log.atWarn().setMessage("대사 보류: 카드사 응답 없음")
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("type", tx.getType())
                    .addKeyValue("cause", e.getCause() == null ? e.getMessage() : e.getCause().toString())
                    .log();
            release(tx);
            return false;
        } catch (RuntimeException e) {
            log.atError().setMessage("대사 실패")
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("type", tx.getType())
                    .setCause(e)
                    .log();
            release(tx);
            return false;
        }
    }

    private boolean reverseApproval(PaymentTransaction tx) {
        cardCompanyClient.reverse(tx.getTransactionKey(), tx.getPaymentKey(), tx.getAmount());
        boolean finished = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (finish(tx, TransactionStatus.FAILED) == 0) {
                return false;
            }
            findPayment(tx).abortByReversal();
            return true;
        }));
        logResolved(tx, finished, "REVERSED");
        return finished;
    }

    private boolean retryCancel(PaymentTransaction tx) {
        Payment payment = findPayment(tx);
        CardCancelResult result = cardCompanyClient.cancel(
                tx.getTransactionKey(), payment.getCard().getApproveNo(), tx.getAmount());
        boolean finished = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (result.canceled()) {
                return finish(tx, TransactionStatus.DONE) == 1;
            }
            if (finish(tx, TransactionStatus.FAILED) == 0) {
                return false;
            }
            paymentRepository.restoreBalance(payment.getId(), tx.getAmount());
            return true;
        }));
        logResolved(tx, finished, result.canceled() ? "CANCELED" : "REJECTED");
        return finished;
    }

    private int finish(PaymentTransaction tx, TransactionStatus next) {
        return transactionRepository.finishIfRequested(tx.getId(), next, timeProvider.now());
    }

    private Payment findPayment(PaymentTransaction tx) {
        return paymentRepository.findByMerchantAndPaymentKey(tx.getMId(), tx.getPaymentKey())
                .orElseThrow(() -> new IllegalStateException("결제가 없습니다. paymentKey=" + tx.getPaymentKey()));
    }

    private void logResolved(PaymentTransaction tx, boolean finished, String result) {
        log.atInfo().setMessage(finished ? "대사 확정" : "대사 건너뜀: 이미 확정된 거래")
                .addKeyValue("transactionKey", tx.getTransactionKey())
                .addKeyValue("type", tx.getType())
                .addKeyValue("result", result)
                .log();
    }
}
