package com.sangyunpark99.pgserver.payment.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConfirmIdempotencyTest {
    private static final String MERCHANT = "m_idem_confirm";

    @Autowired MockMvc mockMvc;
    @Autowired TestMerchants merchants;
    @MockitoBean CardCompanyClient cardCompanyClient;
    @MockitoSpyBean PaymentService paymentService;

    @BeforeEach
    void setUp() {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.approved("12345678", "11", "1234-****-****-5678"));
    }

    @Test
    @DisplayName("같은 키로 승인을 다시 보내면 카드사를 다시 부르지 않고 첫 응답과 같은 본문을 돌려준다")
    void replaysFirstResponse() throws Exception {
        Created payment = created();
        String key = UUID.randomUUID().toString();

        MvcResult first = confirm(payment, key, 10_000);
        MvcResult retry = confirm(payment, key, 10_000);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        verify(cardCompanyClient, times(1)).approve(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("응답이 저장된 중복 승인은 서비스를 거치지 않는다")
    void duplicateSkipsService() throws Exception {
        Created payment = created();
        String key = UUID.randomUUID().toString();
        confirm(payment, key, 10_000);
        clearInvocations(paymentService);

        confirm(payment, key, 10_000);

        verify(paymentService, never()).confirm(any());
    }

    @Test
    @DisplayName("첫 승인이 카드사를 기다리는 동안 같은 키로 다시 오면 409 APPROVAL_IN_PROGRESS")
    void sameKeyWhileFirstInProgress() throws Exception {
        Created payment = created();
        String key = UUID.randomUUID().toString();
        CountDownLatch cardCalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        willAnswer(invocation -> {
            cardCalled.countDown();
            release.await(10, TimeUnit.SECONDS);
            return CardApprovalResult.approved("12345678", "11", "1234-****-****-5678");
        }).given(cardCompanyClient).approve(anyString(), anyString(), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> first = executor.submit(() -> confirm(payment, key, 10_000));
        assertThat(cardCalled.await(10, TimeUnit.SECONDS)).isTrue();

        MvcResult retry = confirm(payment, key, 10_000);
        release.countDown();

        assertThat(retry.getResponse().getStatus()).isEqualTo(409);
        assertThat(retry.getResponse().getContentAsString()).contains("APPROVAL_IN_PROGRESS");
        assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        executor.shutdown();
    }

    @Test
    @DisplayName("같은 키로 다른 금액을 보내면 422 IDEMPOTENCY_KEY_REUSED")
    void sameKeyDifferentBody() throws Exception {
        Created payment = created();
        String key = UUID.randomUUID().toString();
        confirm(payment, key, 10_000);

        MvcResult reused = confirm(payment, key, 9_000);

        assertThat(reused.getResponse().getStatus()).isEqualTo(422);
        assertThat(reused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("같은 키가 동시에 와도 카드사 승인은 한 번이고, 응답은 200이거나 처리 중 409")
    void sameKeyConcurrently() throws Exception {
        Created payment = created();
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<Future<Integer>> results = List.of(
                executor.submit(() -> { start.await(); return confirm(payment, key, 10_000).getResponse().getStatus(); }),
                executor.submit(() -> { start.await(); return confirm(payment, key, 10_000).getResponse().getStatus(); }));
        start.countDown();
        List<Integer> statuses = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertThat(statuses).contains(200).allMatch(code -> code == 200 || code == 409);
        verify(cardCompanyClient, times(1)).approve(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("카드사가 거절한 승인도 같은 키로 다시 오면 첫 응답(ABORTED)을 그대로 돌려준다")
    void replaysRejectedApproval() throws Exception {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.rejected("REJECT_CARD", "한도 초과"));
        Created payment = created();
        String key = UUID.randomUUID().toString();

        MvcResult first = confirm(payment, key, 10_000);
        MvcResult retry = confirm(payment, key, 10_000);

        assertThat(JsonPath.<String>read(first.getResponse().getContentAsString(), "$.status")).isEqualTo("ABORTED");
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        verify(cardCompanyClient, times(1)).approve(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("카드사 응답이 없어 망취소로 끝난 승인도 같은 키로 다시 오면 첫 응답을 그대로 돌려준다")
    void replaysReversedApproval() throws Exception {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willThrow(new CardCompanyUnavailableException("timeout", null));
        Created payment = created();
        String key = UUID.randomUUID().toString();

        MvcResult first = confirm(payment, key, 10_000);
        MvcResult retry = confirm(payment, key, 10_000);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        verify(cardCompanyClient, times(1)).approve(anyString(), anyString(), anyLong());
        verify(cardCompanyClient, times(1)).reverse(anyString(), anyString(), anyLong());
    }

    private record Created(String paymentKey, String orderId) {
    }

    private Created created() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","orderName":"티셔츠","amount":10000}
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        clearInvocations(cardCompanyClient);
        return new Created(JsonPath.read(body, "$.paymentKey"), orderId);
    }

    private MvcResult confirm(Created payment, String key, long amount) throws Exception {
        return mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentKey":"%s","orderId":"%s","amount":%d}
                                """.formatted(payment.paymentKey(), payment.orderId(), amount)))
                .andReturn();
    }
}
