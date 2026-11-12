package com.sangyunpark99.pgserver.payment.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.support.TestMerchants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class CreateIdempotencyTest {
    private static final String MERCHANT = "m_idem_create";

    @Autowired MockMvc mockMvc;
    @Autowired TestMerchants merchants;
    @Autowired PaymentRepository paymentRepository;
    @MockitoSpyBean PaymentService paymentService;

    @Test
    @DisplayName("같은 키로 생성을 다시 보내면 409 대신 첫 응답(같은 결제 키)을 돌려준다")
    void replaysFirstResponse() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        MvcResult first = create(orderId, key, 10_000);
        MvcResult retry = create(orderId, key, 10_000);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("응답이 저장된 중복 생성은 서비스를 거치지 않는다")
    void duplicateSkipsService() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        create(orderId, key, 10_000);
        clearInvocations(paymentService);

        create(orderId, key, 10_000);

        verify(paymentService, never()).create(any());
    }

    @Test
    @DisplayName("같은 키로 다른 금액을 보내면 422 IDEMPOTENCY_KEY_REUSED")
    void sameKeyDifferentBody() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        create(orderId, key, 10_000);

        MvcResult reused = create(orderId, key, 9_000);

        assertThat(reused.getResponse().getStatus()).isEqualTo(422);
        assertThat(reused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("같은 키가 동시에 와도 결제는 하나만 만들어지고 둘 다 같은 결제 키를 받는다")
    void sameKeyConcurrently() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<Future<MvcResult>> results = List.of(
                executor.submit(() -> { start.await(); return create(orderId, key, 10_000); }),
                executor.submit(() -> { start.await(); return create(orderId, key, 10_000); }));
        start.countDown();
        MvcResult a = results.get(0).get(10, TimeUnit.SECONDS);
        MvcResult b = results.get(1).get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(a.getResponse().getStatus()).isEqualTo(200);
        assertThat(b.getResponse().getStatus()).isEqualTo(200);
        String keyA = JsonPath.read(a.getResponse().getContentAsString(), "$.paymentKey");
        String keyB = JsonPath.read(b.getResponse().getContentAsString(), "$.paymentKey");
        assertThat(keyA).isEqualTo(keyB);
    }

    @Test
    @DisplayName("다른 키로 같은 주문번호를 보내면 지금처럼 409 DUPLICATED")
    void differentKeySameOrderId() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        create(orderId, UUID.randomUUID().toString(), 10_000);

        MvcResult second = create(orderId, UUID.randomUUID().toString(), 10_000);

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(second.getResponse().getContentAsString()).contains("DUPLICATED");
    }

    private MvcResult create(String orderId, String key, long amount) throws Exception {
        return mockMvc.perform(post("/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","orderName":"티셔츠","amount":%d}
                                """.formatted(orderId, amount)))
                .andReturn();
    }
}
