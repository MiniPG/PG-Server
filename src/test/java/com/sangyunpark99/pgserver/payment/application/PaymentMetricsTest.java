package com.sangyunpark99.pgserver.payment.application;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.support.TestMerchants;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentMetricsTest {
    private static final String MERCHANT = "m_metrics";

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry registry;
    @Autowired TestMerchants merchants;

    @MockitoBean CardCompanyClient cardCompanyClient;

    @BeforeEach
    void setUp() {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.approved("12345678", "11", "1234-****-****-5678"));
        given(cardCompanyClient.cancel(anyString(), anyString(), anyLong()))
                .willReturn(CardCancelResult.success());
    }

    @Test
    @DisplayName("승인 결과별로 payment.approve 카운터가 1씩 늘어난다")
    void approveCounter() throws Exception {
        double approved = counter("payment.approve", PaymentMetrics.APPROVED);
        double rejected = counter("payment.approve", PaymentMetrics.REJECTED);
        double unavailable = counter("payment.approve", PaymentMetrics.UNAVAILABLE);

        approve();
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.rejected("REJECT_CARD", "거절"));
        approve();
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willThrow(new CardCompanyUnavailableException("timeout", null));
        approve();

        assertThat(counter("payment.approve", PaymentMetrics.APPROVED)).isEqualTo(approved + 1);
        assertThat(counter("payment.approve", PaymentMetrics.REJECTED)).isEqualTo(rejected + 1);
        assertThat(counter("payment.approve", PaymentMetrics.UNAVAILABLE)).isEqualTo(unavailable + 1);
    }

    @Test
    @DisplayName("카드사 응답을 못 받으면 결과 불명(REQUESTED) 거래 게이지가 1 늘어난다")
    void requestedGauge() throws Exception {
        double before = registry.get("payment.transactions.requested").gauge().value();
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willThrow(new CardCompanyUnavailableException("timeout", null));

        approve();

        assertThat(registry.get("payment.transactions.requested").gauge().value()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("취소 성공 시 payment.cancel{result=canceled}가 1 늘어난다")
    void cancelCounter() throws Exception {
        String paymentKey = approve();
        double canceled = counter("payment.cancel", PaymentMetrics.CANCELED);

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cancelReason":"반품"}
                        """));

        assertThat(counter("payment.cancel", PaymentMetrics.CANCELED)).isEqualTo(canceled + 1);
    }

    private String approve() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String created = mockMvc.perform(post("/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","orderName":"티셔츠","amount":10000}
                                """.formatted(orderId)))
                .andReturn().getResponse().getContentAsString();
        String paymentKey = JsonPath.read(created, "$.paymentKey");
        mockMvc.perform(post("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentKey":"%s","orderId":"%s","amount":10000}
                        """.formatted(paymentKey, orderId)));
        return paymentKey;
    }

    private double counter(String name, String result) {
        var counter = registry.find(name).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
