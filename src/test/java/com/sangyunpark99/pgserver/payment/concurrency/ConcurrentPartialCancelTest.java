package com.sangyunpark99.pgserver.payment.concurrency;

import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.application.CancelPaymentCommand;
import com.sangyunpark99.pgserver.payment.application.ConfirmPaymentCommand;
import com.sangyunpark99.pgserver.payment.application.CreatePaymentCommand;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import com.sangyunpark99.pgserver.support.DelayedCardCompanyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ConcurrentPartialCancelTest.CardConfig.class)
class ConcurrentPartialCancelTest {

    private static final int REQUESTS = 5;
    private static final long TOTAL = 10_000;
    private static final long CANCEL = 3_000;

    @TestConfiguration
    static class CardConfig {
        @Bean
        @Primary
        DelayedCardCompanyClient delayedCardCompanyClient() {
            return new DelayedCardCompanyClient(500);
        }
    }

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    @Autowired
    private CardCompanyClient cardCompanyClient;

    @BeforeEach
    void setUp() {
        card().reset();
    }

    @Test
    @DisplayName("같은 결제에 부분 취소가 동시에 와도 남은 금액이 맞고, 결제 금액보다 많이 환불되지 않는다")
    void partialCancelConcurrently() throws Exception {
        String mId = "m_" + UUID.randomUUID().toString().substring(0, 8);
        String orderId = "order-" + UUID.randomUUID();
        Payment created = paymentService.create(new CreatePaymentCommand(mId, orderId, "티셔츠", TOTAL, true));
        paymentService.confirm(new ConfirmPaymentCommand(mId, created.getPaymentKey(), orderId, TOTAL));
        card().reset();

        List<String> results = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < REQUESTS; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    paymentService.cancel(new CancelPaymentCommand(mId, created.getPaymentKey(), "부분 반품", CANCEL));
                    results.add("성공");
                } catch (Exception e) {
                    results.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long balance = paymentRepository.findByPaymentKey(created.getPaymentKey()).orElseThrow().getBalanceAmount();
        long canceledDone = transactionRepository
                .findByPaymentKeyAndTypeOrderByIdAsc(created.getPaymentKey(), TransactionType.CANCEL).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.DONE)
                .mapToLong(tx -> tx.getAmount())
                .sum();
        long successes = results.stream().filter("성공"::equals).count();
        int cardCalls = card().cancelCalls();

        System.out.printf("[동시 부분 취소] 요청 %d × %d원, 카드사 취소 호출 %d, 취소 DONE 합 %d, 남은 금액 %d, 결과 %s%n",
                REQUESTS, CANCEL, cardCalls, canceledDone, balance, results);

        assertThat(canceledDone).isLessThanOrEqualTo(TOTAL);
        assertThat(TOTAL - canceledDone).isEqualTo(balance);
        assertThat(cardCalls).isEqualTo(successes);
        assertThat(successes).isEqualTo(TOTAL / CANCEL);
    }

    private DelayedCardCompanyClient card() {
        return (DelayedCardCompanyClient) cardCompanyClient;
    }
}
