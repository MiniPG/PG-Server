package com.sangyunpark99.pgserver.payment.concurrency;

import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.application.ConfirmPaymentCommand;
import com.sangyunpark99.pgserver.payment.application.CreatePaymentCommand;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
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
@Import(ConcurrentApproveTest.CardConfig.class)
class ConcurrentApproveTest {

    private static final int REQUESTS = 5;

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
    private PaymentTransactionRepository transactionRepository;
    @Autowired
    private CardCompanyClient cardCompanyClient;

    @BeforeEach
    void setUp() {
        ((DelayedCardCompanyClient) cardCompanyClient).reset();
    }

    @Test
    @DisplayName("같은 결제에 승인 요청이 동시에 와도 카드사 승인은 한 번만 일어난다")
    void approveOnlyOnce() throws Exception {
        String mId = "m_" + UUID.randomUUID().toString().substring(0, 8);
        String orderId = "order-" + UUID.randomUUID();
        Payment payment = paymentService.create(new CreatePaymentCommand(mId, orderId, "티셔츠", 10_000, true));

        List<String> results = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < REQUESTS; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    paymentService.confirm(new ConfirmPaymentCommand(mId, payment.getPaymentKey(), orderId, 10_000, null, null));
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

        List<PaymentTransaction> approves = transactionRepository
                .findByPaymentKeyAndTypeOrderByIdAsc(payment.getPaymentKey(), TransactionType.APPROVE);
        long done = approves.stream().filter(tx -> tx.getStatus() == TransactionStatus.DONE).count();
        int cardCalls = ((DelayedCardCompanyClient) cardCompanyClient).approveCalls();

        System.out.printf("[동시 승인] 요청 %d, 카드사 승인 호출 %d, 승인 거래 DONE %d, 결과 %s%n",
                REQUESTS, cardCalls, done, results);

        assertThat(cardCalls).isEqualTo(1);
        assertThat(done).isEqualTo(1);
        assertThat(results).filteredOn("성공"::equals).hasSize(1);
        assertThat(results).filteredOn(r -> !r.equals("성공"))
                .hasSize(REQUESTS - 1)
                .allMatch(r -> r.contains("승인을 처리하고 있습니다"));
    }
}
