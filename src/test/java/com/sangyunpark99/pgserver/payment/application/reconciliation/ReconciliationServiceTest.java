package com.sangyunpark99.pgserver.payment.application.reconciliation;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.common.time.TimeProvider;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import com.sangyunpark99.pgserver.support.TestMerchants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationServiceTest {
    private static final String MERCHANT = "m_reconcile";

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestMerchants merchants;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TimeProvider timeProvider;

    @MockitoBean
    private CardCompanyClient cardCompanyClient;

    @BeforeEach
    void setUp() {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.approved("12345678", "11", "1234-****-****-5678"));
        given(cardCompanyClient.cancel(anyString(), anyString(), anyLong()))
                .willReturn(CardCancelResult.success());
    }

    @Test
    @DisplayName("결과 모름으로 남은 승인 거래는 같은 키로 망취소해 결제 ABORTED, 거래 FAILED로 확정한다")
    void reverseUnknownApproval() throws Exception {
        String paymentKey = unknownApproval(15_000);
        PaymentTransaction tx = approveTx(paymentKey);
        willDoNothing().given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());

        reconciliationService.reconcile(later());

        verify(cardCompanyClient).reverse(tx.getTransactionKey(), paymentKey, 15_000);
        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(payment.getFailureCode()).isEqualTo("APPROVAL_REVERSED");
        assertThat(approveTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(approveTx(paymentKey).getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("망취소도 응답이 없으면 그대로 두고 다음 실행에서 다시 시도한다")
    void keepWhenReversalUnavailable() throws Exception {
        String paymentKey = unknownApproval(15_000);

        reconciliationService.reconcile(later());

        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVING);
        assertThat(approveTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.REQUESTED);

        willDoNothing().given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());
        reconciliationService.reconcile(later());

        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getStatus()).isEqualTo(PaymentStatus.ABORTED);
    }

    @Test
    @DisplayName("결과 모름으로 남은 취소 거래는 같은 키로 다시 취소해 성공하면 거래 DONE, 잔액은 차감된 채로 둔다")
    void retryUnknownCancel() throws Exception {
        String paymentKey = unknownCancel(10_000, 3_000);
        PaymentTransaction tx = cancelTx(paymentKey);

        reconciliationService.reconcile(later());

        verify(cardCompanyClient).cancel(eq(tx.getTransactionKey()), anyString(), eq(3_000L));
        assertThat(cancelTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.DONE);
        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        assertThat(payment.getBalanceAmount()).isEqualTo(7_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
    }

    @Test
    @DisplayName("다시 보낸 취소를 카드사가 거절하면 잔액을 되돌리고 거래 FAILED")
    void restoreWhenCancelRejected() throws Exception {
        String paymentKey = unknownCancel(10_000, 3_000);
        willReturn(CardCancelResult.rejected("REJECT_CANCEL", "거절"))
                .given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());

        reconciliationService.reconcile(later());

        assertThat(cancelTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.FAILED);
        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        assertThat(payment.getBalanceAmount()).isEqualTo(10_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("기준 시간이 지나지 않은 거래는 처리 중인 요청일 수 있어 건드리지 않는다")
    void skipRecentTransaction() throws Exception {
        String paymentKey = unknownApproval(15_000);
        willDoNothing().given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());

        reconciliationService.reconcile(LocalDateTime.now());

        verify(cardCompanyClient, never()).reverse(eq(approveTx(paymentKey).getTransactionKey()), anyString(), anyLong());
        assertThat(approveTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.REQUESTED);
    }

    @Test
    @DisplayName("두 서버가 동시에 같은 취소 거래를 확정해도 잔액은 한 번만 되돌린다")
    void restoreOnceWhenReconciledConcurrently() throws Exception {
        String paymentKey = unknownCancel(10_000, 3_000);
        willReturn(CardCancelResult.rejected("REJECT_CANCEL", "거절"))
                .given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        LocalDateTime now = later();
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                start.await();
                return reconciliationService.reconcile(now);
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        System.out.printf("[동시 확정] 잔액 %d, 상태 %s, 거래 %s%n",
                payment.getBalanceAmount(), payment.getStatus(), cancelTx(paymentKey).getStatus());
        assertThat(payment.getBalanceAmount()).isEqualTo(10_000);
        assertThat(cancelTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    @DisplayName("결과 모름이 한 묶음(100건)보다 많아도 한 번 실행에서 모두 확정하고, 카드사 망취소는 최대 동시 수까지 함께 보낸다")
    void reconcileAllConcurrently() throws Exception {
        List<String> paymentKeys = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            paymentKeys.add(unknownApproval(15_000));
        }
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        willAnswer(invocation -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.sleep(50);
            inFlight.decrementAndGet();
            return null;
        }).given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());

        long start = System.currentTimeMillis();
        reconciliationService.reconcile(later());
        long elapsed = System.currentTimeMillis() - start;

        long aborted = paymentKeys.stream()
                .map(key -> paymentRepository.findByPaymentKey(key).orElseThrow().getStatus())
                .filter(status -> status == PaymentStatus.ABORTED)
                .count();
        System.out.printf("[대사 병렬] 확정 %d/150, 최대 동시 망취소 %d, %dms%n", aborted, maxInFlight.get(), elapsed);
        assertThat(aborted).isEqualTo(150);
        assertThat(maxInFlight.get()).isGreaterThan(1).isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("두 서버가 동시에 대사를 돌려도 결과 모름 거래마다 카드사 망취소는 한 번만 보낸다")
    void reverseOnceAcrossServers() throws Exception {
        List<String> paymentKeys = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            paymentKeys.add(unknownApproval(15_000));
        }
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        willAnswer(invocation -> {
            calls.computeIfAbsent(invocation.getArgument(1), key -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(200);
            return null;
        }).given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());

        ReconciliationService otherServer = new ReconciliationService(transactionRepository, paymentRepository,
                cardCompanyClient, transactionTemplate, timeProvider, 20);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        LocalDateTime now = later();
        var first = executor.submit(() -> {
            start.await();
            return reconciliationService.reconcile(now);
        });
        var second = executor.submit(() -> {
            start.await();
            return otherServer.reconcile(now);
        });
        start.countDown();
        int resolvedByFirst = first.get(60, TimeUnit.SECONDS);
        int resolvedBySecond = second.get(60, TimeUnit.SECONDS);
        executor.shutdown();
        otherServer.shutdown();

        int totalCalls = paymentKeys.stream().mapToInt(key -> calls.getOrDefault(key, new AtomicInteger()).get()).sum();
        long aborted = paymentKeys.stream()
                .map(key -> paymentRepository.findByPaymentKey(key).orElseThrow().getStatus())
                .filter(status -> status == PaymentStatus.ABORTED)
                .count();
        System.out.printf("[두 서버 대사] 확정 %d + %d, 결제 ABORTED %d/200, 카드사 망취소 %d회%n",
                resolvedByFirst, resolvedBySecond, aborted, totalCalls);
        assertThat(aborted).isEqualTo(200);
        assertThat(totalCalls).isEqualTo(200);
    }

    private String unknownApproval(long amount) throws Exception {
        willThrow(new CardCompanyUnavailableException("timeout", null))
                .given(cardCompanyClient).approve(anyString(), anyString(), anyLong());
        willThrow(new CardCompanyUnavailableException("timeout", null))
                .given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());
        String orderId = newOrderId();
        String paymentKey = create(orderId, amount);
        confirm(paymentKey, orderId, amount);
        clearInvocations(cardCompanyClient);
        return paymentKey;
    }

    private String unknownCancel(long amount, long cancelAmount) throws Exception {
        String orderId = newOrderId();
        String paymentKey = create(orderId, amount);
        confirm(paymentKey, orderId, amount);
        willThrow(new CardCompanyUnavailableException("timeout", null))
                .given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());
        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cancelReason":"반품","cancelAmount":%d}
                        """.formatted(cancelAmount)));
        willReturn(CardCancelResult.success())
                .given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());
        clearInvocations(cardCompanyClient);
        return paymentKey;
    }

    private String create(String orderId, long amount) throws Exception {
        String response = mockMvc.perform(post("/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","orderName":"티셔츠","amount":%d,"partialCancelable":true}
                                """.formatted(orderId, amount)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.paymentKey");
    }

    private void confirm(String paymentKey, String orderId, long amount) throws Exception {
        mockMvc.perform(post("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentKey":"%s","orderId":"%s","amount":%d}
                        """.formatted(paymentKey, orderId, amount)));
    }

    private PaymentTransaction approveTx(String paymentKey) {
        return transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.APPROVE).getFirst();
    }

    private PaymentTransaction cancelTx(String paymentKey) {
        return transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL).getFirst();
    }

    private LocalDateTime later() {
        return LocalDateTime.now().plusMinutes(1);
    }

    private String newOrderId() {
        return "order-" + UUID.randomUUID();
    }
}
