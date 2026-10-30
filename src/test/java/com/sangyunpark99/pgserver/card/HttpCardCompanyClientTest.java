package com.sangyunpark99.pgserver.card;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpCardCompanyClientTest {

    private MockRestServiceServer server;
    private HttpCardCompanyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://card");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpCardCompanyClient(builder.build());
    }

    @Test
    @DisplayName("승인 요청을 보내고 승인 결과로 바꾼다")
    void approve() {
        server.expect(requestTo("http://card/v1/approvals"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"transactionKey":"tx_1","paymentKey":"pk_1","amount":10000}
                        """))
                .andRespond(withSuccess("""
                        {"transactionKey":"tx_1","approved":true,"approveNo":"00000001",
                         "issuerCode":"11","maskedNumber":"1234-****-****-5678"}
                        """, MediaType.APPLICATION_JSON));

        CardApprovalResult result = client.approve("tx_1", "pk_1", 10_000);

        assertThat(result.approved()).isTrue();
        assertThat(result.approveNo()).isEqualTo("00000001");
        assertThat(result.issuerCode()).isEqualTo("11");
        server.verify();
    }

    @Test
    @DisplayName("카드사가 거절하면 거절 결과로 바꾼다")
    void approveRejected() {
        server.expect(requestTo("http://card/v1/approvals"))
                .andRespond(withSuccess("""
                        {"transactionKey":"tx_1","approved":false,
                         "failureCode":"REJECT_CARD","failureMessage":"거절"}
                        """, MediaType.APPLICATION_JSON));

        CardApprovalResult result = client.approve("tx_1", "pk_1", 10_000);

        assertThat(result.approved()).isFalse();
        assertThat(result.failureCode()).isEqualTo("REJECT_CARD");
    }

    @Test
    @DisplayName("취소 요청에 원 승인번호를 담아 보낸다")
    void cancel() {
        server.expect(requestTo("http://card/v1/cancels"))
                .andExpect(content().json("""
                        {"transactionKey":"tx_2","approveNo":"00000001","amount":3000}
                        """))
                .andRespond(withSuccess("""
                        {"transactionKey":"tx_2","canceled":true}
                        """, MediaType.APPLICATION_JSON));

        CardCancelResult result = client.cancel("tx_2", "00000001", 3_000);

        assertThat(result.canceled()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("타임아웃이면 결과를 모르는 상태로 보고 CardCompanyUnavailableException")
    void timeout() {
        server.expect(requestTo("http://card/v1/approvals"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.approve("tx_1", "pk_1", 10_000))
                .isInstanceOf(CardCompanyUnavailableException.class);
    }

    @Test
    @DisplayName("카드사 5xx도 결과를 모르는 상태로 본다")
    void serverError() {
        server.expect(requestTo("http://card/v1/cancels"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.cancel("tx_2", "00000001", 3_000))
                .isInstanceOf(CardCompanyUnavailableException.class);
    }
}
