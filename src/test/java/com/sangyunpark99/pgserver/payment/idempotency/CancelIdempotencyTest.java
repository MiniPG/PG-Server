package com.sangyunpark99.pgserver.payment.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CancelIdempotencyTest {
    private static final String MERCHANT = "m_idempotency";

    @Autowired MockMvc mockMvc;
    @Autowired TestMerchants merchants;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentTransactionRepository transactionRepository;
    @MockitoBean CardCompanyClient cardCompanyClient;
    @MockitoSpyBean PaymentService paymentService;

    @BeforeEach
    void setUp() {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.approved("12345678", "11", "1234-****-****-5678"));
        given(cardCompanyClient.cancel(anyString(), anyString(), anyLong()))
                .willReturn(CardCancelResult.success());
    }

    @Test
    @DisplayName("응답을 못 받은 가맹점이 같은 Idempotency-Key로 부분 취소를 다시 보내도 한 번만 취소된다")
    void sameKeyCancelsOnce() throws Exception {
        String paymentKey = approvedPayment();
        String key = UUID.randomUUID().toString();

        MvcResult first = cancel(paymentKey, key, 3_000);
        MvcResult retry = cancel(paymentKey, key, 3_000);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getBalanceAmount()).isEqualTo(7_000);
        assertThat(transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL)).hasSize(1);
    }

    @Test
    @DisplayName("Idempotency-Key가 다르면 같은 금액이라도 별개의 취소로 처리한다")
    void differentKeysCancelTwice() throws Exception {
        String paymentKey = approvedPayment();

        cancel(paymentKey, UUID.randomUUID().toString(), 3_000);
        cancel(paymentKey, UUID.randomUUID().toString(), 3_000);

        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getBalanceAmount()).isEqualTo(4_000);
        assertThat(transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL)).hasSize(2);
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 동시에 두 번 와도 한 번만 취소되고 500은 나지 않는다")
    void sameKeyConcurrently() throws Exception {
        String paymentKey = approvedPayment();
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<Future<Integer>> results = List.of(
                executor.submit(() -> { start.await(); return cancel(paymentKey, key, 3_000).getResponse().getStatus(); }),
                executor.submit(() -> { start.await(); return cancel(paymentKey, key, 3_000).getResponse().getStatus(); }));
        start.countDown();
        List<Integer> statuses = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertThat(statuses).contains(200).allMatch(code -> code == 200 || code == 409);
        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getBalanceAmount()).isEqualTo(7_000);
        assertThat(transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL)).hasSize(1);
    }

    @Test
    @DisplayName("첫 취소가 카드사 응답을 기다리는 동안 같은 키로 다시 오면 409 CANCEL_IN_PROGRESS")
    void sameKeyWhileFirstInProgress() throws Exception {
        String paymentKey = approvedPayment();
        String key = UUID.randomUUID().toString();
        CountDownLatch cardCalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        willAnswer(invocation -> {
            cardCalled.countDown();
            release.await(10, TimeUnit.SECONDS);
            return CardCancelResult.success();
        }).given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> first = executor.submit(() -> cancel(paymentKey, key, 3_000));
        assertThat(cardCalled.await(10, TimeUnit.SECONDS)).isTrue();

        MvcResult retry = cancel(paymentKey, key, 3_000);
        release.countDown();

        assertThat(retry.getResponse().getStatus()).isEqualTo(409);
        assertThat(retry.getResponse().getContentAsString()).contains("CANCEL_IN_PROGRESS");
        assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        executor.shutdown();
        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getBalanceAmount()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 다른 금액을 보내면 422 IDEMPOTENCY_KEY_REUSED이고 취소하지 않는다")
    void sameKeyDifferentAmount() throws Exception {
        String paymentKey = approvedPayment();
        String key = UUID.randomUUID().toString();

        cancel(paymentKey, key, 3_000);
        MvcResult reused = cancel(paymentKey, key, 5_000);

        assertThat(reused.getResponse().getStatus()).isEqualTo(422);
        assertThat(reused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getBalanceAmount()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("같은 키로 다시 오면, 그 사이 다른 취소가 있었어도 첫 응답과 같은 본문을 돌려준다")
    void replaysFirstResponseBody() throws Exception {
        String paymentKey = approvedPayment();
        String key = UUID.randomUUID().toString();

        String first = cancel(paymentKey, key, 3_000).getResponse().getContentAsString();
        cancel(paymentKey, UUID.randomUUID().toString(), 2_000);
        MvcResult retry = cancel(paymentKey, key, 3_000);

        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first);
        assertThat(JsonPath.<Integer>read(retry.getResponse().getContentAsString(), "$.balanceAmount")).isEqualTo(7_000);
    }

    @Test
    @DisplayName("응답이 저장된 중복 요청은 서비스를 거치지 않고 저장된 응답으로 끝난다")
    void duplicateSkipsService() throws Exception {
        String paymentKey = approvedPayment();
        String key = UUID.randomUUID().toString();
        cancel(paymentKey, key, 3_000);
        clearInvocations(paymentService, cardCompanyClient);

        MvcResult retry = cancel(paymentKey, key, 3_000);

        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        verify(paymentService, never()).cancel(any());
        verify(cardCompanyClient, never()).cancel(anyString(), anyString(), anyLong());
    }

    private String approvedPayment() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","orderName":"티셔츠","amount":10000,"partialCancelable":true}
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String paymentKey = JsonPath.read(body, "$.paymentKey");
        mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentKey":"%s","orderId":"%s","amount":10000}
                                """.formatted(paymentKey, orderId)))
                .andExpect(status().isOk());
        return paymentKey;
    }

    private MvcResult cancel(String paymentKey, String idempotencyKey, long amount) throws Exception {
        return mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", paymentKey)
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cancelReason":"단순 변심","cancelAmount":%d}
                                """.formatted(amount)))
                .andReturn();
    }
}
