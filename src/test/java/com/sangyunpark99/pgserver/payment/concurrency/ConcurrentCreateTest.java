package com.sangyunpark99.pgserver.payment.concurrency;

import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.support.TestMerchants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentCreateTest {

    private static final int REQUESTS = 5;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestMerchants merchants;
    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("같은 가맹점이 같은 주문 번호로 결제를 동시에 만들면 하나만 만들어지고 나머지는 409 DUPLICATED")
    void createOnlyOnce() throws Exception {
        String mId = "m_" + UUID.randomUUID().toString().substring(0, 8);
        String orderId = "order-" + UUID.randomUUID();
        String authorization = merchants.authorization(mId);
        String body = """
                {"orderId":"%s","orderName":"티셔츠","amount":10000}
                """.formatted(orderId);

        List<String> results = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < REQUESTS; i++) {
            executor.submit(() -> {
                ready.countDown();
                start.await();
                MvcResult result = mockMvc.perform(post("/v1/payments")
                                .header(HttpHeaders.AUTHORIZATION, authorization)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
                results.add(result.getResponse().getStatus() + " " + result.getResponse().getContentAsString());
                return null;
            });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        System.out.printf("[동시 결제 생성] 요청 %d, 결과 %s%n", REQUESTS, results);

        assertThat(results).filteredOn(r -> r.startsWith("200")).hasSize(1);
        assertThat(results).filteredOn(r -> !r.startsWith("200"))
                .hasSize(REQUESTS - 1)
                .allMatch(r -> r.startsWith("409") && r.contains("DUPLICATED"));
        assertThat(paymentRepository.findByMerchantAndOrderId(mId, orderId)).isPresent();
    }
}
