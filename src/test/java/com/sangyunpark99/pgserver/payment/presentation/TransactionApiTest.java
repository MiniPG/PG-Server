package com.sangyunpark99.pgserver.payment.presentation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionApiTest {

    @Autowired MockMvc mockMvc;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    @DisplayName("기간 안의 내 승인·취소 거래를 오래된 순으로 조회한다")
    void search() throws Exception {
        String mId = newMerchant();
        String paymentKey = approve(mId, 10_000);
        cancel(mId, paymentKey, 3_000);

        search(mId, now.minusHours(1), now.plusHours(1), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("APPROVE"))
                .andExpect(jsonPath("$[0].amount").value(10_000))
                .andExpect(jsonPath("$[1].type").value("CANCEL"))
                .andExpect(jsonPath("$[1].amount").value(3_000))
                .andExpect(jsonPath("$[1].paymentKey").value(paymentKey));
    }

    @Test
    @DisplayName("다른 가맹점의 거래는 보이지 않는다")
    void otherMerchant() throws Exception {
        String mine = newMerchant();
        String other = newMerchant();
        approve(mine, 10_000);
        approve(other, 20_000);

        search(mine, now.minusHours(1), now.plusHours(1), null, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(10_000));
    }

    @Test
    @DisplayName("기간 밖의 거래는 보이지 않는다")
    void outOfPeriod() throws Exception {
        String mId = newMerchant();
        approve(mId, 10_000);

        search(mId, now.minusDays(2), now.minusDays(1), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("limit과 startingAfter로 다음 페이지를 이어서 조회한다")
    void cursorPaging() throws Exception {
        String mId = newMerchant();
        for (int i = 1; i <= 5; i++) {
            approve(mId, i * 1_000);
        }

        String first = search(mId, now.minusHours(1), now.plusHours(1), null, 2)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amount").value(1_000))
                .andExpect(jsonPath("$[1].amount").value(2_000))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$[1].transactionKey");

        String second = search(mId, now.minusHours(1), now.plusHours(1), cursor, 2)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amount").value(3_000))
                .andExpect(jsonPath("$[1].amount").value(4_000))
                .andReturn().getResponse().getContentAsString();
        cursor = JsonPath.read(second, "$[1].transactionKey");

        search(mId, now.minusHours(1), now.plusHours(1), cursor, 2)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(5_000));
    }

    @Test
    @DisplayName("다른 가맹점의 transactionKey를 커서로 쓰면 400")
    void otherMerchantCursor() throws Exception {
        String mine = newMerchant();
        String other = newMerchant();
        approve(other, 10_000);
        String response = search(other, now.minusHours(1), now.plusHours(1), null, null)
                .andReturn().getResponse().getContentAsString();
        String otherKey = JsonPath.read(response, "$[0].transactionKey");

        search(mine, now.minusHours(1), now.plusHours(1), otherKey, null)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("startDate가 endDate보다 늦으면 400")
    void invalidPeriod() throws Exception {
        search(newMerchant(), now, now.minusHours(1), null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("limit이 범위를 벗어나면 400")
    void invalidLimit() throws Exception {
        search(newMerchant(), now.minusHours(1), now, null, 0)
                .andExpect(status().isBadRequest());
        search(newMerchant(), now.minusHours(1), now, null, 10_001)
                .andExpect(status().isBadRequest());
    }

    private String newMerchant() {
        return "m_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String approve(String mId, long amount) throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String created = mockMvc.perform(post("/v1/payments")
                        .header("X-Merchant-Id", mId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","orderName":"티셔츠","amount":%d}
                                """.formatted(orderId, amount)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String paymentKey = JsonPath.read(created, "$.paymentKey");

        mockMvc.perform(post("/v1/payments/confirm")
                        .header("X-Merchant-Id", mId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentKey":"%s","orderId":"%s","amount":%d}
                                """.formatted(paymentKey, orderId, amount)))
                .andExpect(status().isOk());
        return paymentKey;
    }

    private void cancel(String mId, String paymentKey, long amount) throws Exception {
        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", paymentKey)
                        .header("X-Merchant-Id", mId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cancelReason":"반품","cancelAmount":%d}
                                """.formatted(amount)))
                .andExpect(status().isOk());
    }

    private ResultActions search(String mId, LocalDateTime start, LocalDateTime end,
                                 String startingAfter, Integer limit) throws Exception {
        var request = get("/v1/transactions")
                .header("X-Merchant-Id", mId)
                .param("startDate", start.toString())
                .param("endDate", end.toString());
        if (startingAfter != null) {
            request.param("startingAfter", startingAfter);
        }
        if (limit != null) {
            request.param("limit", String.valueOf(limit));
        }
        return mockMvc.perform(request);
    }
}
