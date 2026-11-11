package com.sangyunpark99.pgserver.payment.concurrency;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import com.sangyunpark99.pgserver.support.TestMerchants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "card.bulkhead.max-concurrent-calls=1")
@AutoConfigureMockMvc
class CardBulkheadTest {
    private static final String MERCHANT = "m_bulkhead";
    private static final CardApprovalResult APPROVED =
            CardApprovalResult.approved("12345678", "11", "1234-****-****-5678");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestMerchants merchants;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @MockitoBean
    private CardCompanyClient cardCompanyClient;

    @Test
    @DisplayName("카드사 호출 자리가 다 차 있으면 승인은 카드사를 부르지 않고 바로 503 CARD_COMPANY_BUSY로 거절하고, 결제는 그대로 남아 같은 키로 다시 승인할 수 있다")
    void rejectWhenFullThenRetry() throws Exception {
        String slowOrder = orderId();
        String slowKey = create(slowOrder);
        String busyOrder = orderId();
        String busyKey = create(busyOrder);

        CountDownLatch cardCalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        willAnswer(invocation -> {
            cardCalled.countDown();
            release.await(10, TimeUnit.SECONDS);
            return APPROVED;
        }).given(cardCompanyClient).approve(anyString(), eq(slowKey), anyLong());
        willAnswer(invocation -> APPROVED).given(cardCompanyClient).approve(anyString(), eq(busyKey), anyLong());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> slow = executor.submit(() -> confirm(slowKey, slowOrder));
        assertThat(cardCalled.await(10, TimeUnit.SECONDS)).isTrue();

        long start = System.currentTimeMillis();
        MvcResult busy = confirm(busyKey, busyOrder);
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("[벌크헤드] 자리 없을 때 응답 %d %s, %dms%n",
                busy.getResponse().getStatus(), busy.getResponse().getContentAsString(), elapsed);
        assertThat(busy.getResponse().getStatus()).isEqualTo(503);
        assertThat(JsonPath.<String>read(busy.getResponse().getContentAsString(), "$.code")).isEqualTo("CARD_COMPANY_BUSY");
        assertThat(elapsed).isLessThan(500);
        assertThat(paymentRepository.findByPaymentKey(busyKey).orElseThrow().getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(busyKey, TransactionType.APPROVE)).isEmpty();
        verify(cardCompanyClient, never()).approve(anyString(), eq(busyKey), anyLong());

        release.countDown();
        assertThat(slow.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        executor.shutdown();

        MvcResult retried = confirm(busyKey, busyOrder);
        assertThat(retried.getResponse().getStatus()).isEqualTo(200);
        assertThat(paymentRepository.findByPaymentKey(busyKey).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    private String create(String orderId) throws Exception {
        String response = mockMvc.perform(post("/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","orderName":"티셔츠","amount":10000,"partialCancelable":true}
                                """.formatted(orderId)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.paymentKey");
    }

    private MvcResult confirm(String paymentKey, String orderId) throws Exception {
        return mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentKey":"%s","orderId":"%s","amount":10000}
                                """.formatted(paymentKey, orderId)))
                .andReturn();
    }

    private String orderId() {
        return "order-" + UUID.randomUUID();
    }
}
