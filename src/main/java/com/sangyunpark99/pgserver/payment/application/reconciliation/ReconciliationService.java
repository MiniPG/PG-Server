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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {
    static final Duration THRESHOLD = Duration.ofSeconds(10);
    private static final int BATCH_SIZE = 100;

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final CardCompanyClient cardCompanyClient;
    private final TransactionTemplate transactionTemplate;
    private final TimeProvider timeProvider;

    public int reconcile(LocalDateTime now) {
        List<PaymentTransaction> targets = transactionRepository.findUnresolved(
                now.minus(THRESHOLD), PageRequest.of(0, BATCH_SIZE));
        int resolved = 0;
        for (PaymentTransaction tx : targets) {
            if (resolve(tx)) {
                resolved++;
            }
        }
        return resolved;
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
            return false;
        } catch (RuntimeException e) {
            log.atError().setMessage("대사 실패")
                    .addKeyValue("transactionKey", tx.getTransactionKey())
                    .addKeyValue("type", tx.getType())
                    .setCause(e)
                    .log();
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
