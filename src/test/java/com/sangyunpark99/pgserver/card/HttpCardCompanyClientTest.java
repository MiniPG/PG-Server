package com.sangyunpark99.pgserver.card;

import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;

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
    @DisplayName("망취소는 원래 승인의 transactionKey로 보낸다")
    void reverse() {
        server.expect(requestTo("http://card/v1/approvals/tx_1/reversal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"paymentKey":"pk_1","amount":10000}
                        """))
                .andRespond(withSuccess("""
                        {"transactionKey":"tx_1","status":"REVERSED"}
                        """, MediaType.APPLICATION_JSON));

        client.reverse("tx_1", "pk_1", 10_000);

        server.verify();
    }

    @Test
    @DisplayName("망취소 결과가 승인으로 남아 있으면 결과를 모르는 상태로 본다")
    void reverseStillApproved() {
        server.expect(requestTo("http://card/v1/approvals/tx_1/reversal"))
                .andRespond(withSuccess("""
                        {"transactionKey":"tx_1","status":"APPROVED"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.reverse("tx_1", "pk_1", 10_000))
                .isInstanceOf(CardCompanyUnavailableException.class);
    }

    @Test
    @DisplayName("거래 목록은 기간·커서·limit을 쿼리 파라미터로 보내고 페이지로 바꾼다")
    void listTransactions() {
        server.expect(requestTo("http://card/v1/transactions?from=2026-11-03T00:00&to=2026-11-04T00:00&limit=1000&startingAfter=tx_9"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"transactions":[{"transactionKey":"tx_10","type":"APPROVE","amount":10000,"status":"APPROVED"}],
                         "hasMore":true}
                        """, MediaType.APPLICATION_JSON));

        CardTransactionPage page = client.listTransactions(
                LocalDateTime.of(2026, 11, 3, 0, 0), LocalDateTime.of(2026, 11, 4, 0, 0), "tx_9", 1_000);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.transactions()).containsExactly(new CardTransactionRecord("tx_10", "APPROVE", 10_000, "APPROVED"));
        server.verify();
    }

    @Test
    @DisplayName("첫 페이지는 startingAfter 없이 보낸다")
    void listFirstPage() {
        server.expect(requestTo("http://card/v1/transactions?from=2026-11-03T00:00&to=2026-11-04T00:00&limit=1000"))
                .andRespond(withSuccess("""
                        {"transactions":[],"hasMore":false}
                        """, MediaType.APPLICATION_JSON));

        CardTransactionPage page = client.listTransactions(
                LocalDateTime.of(2026, 11, 3, 0, 0), LocalDateTime.of(2026, 11, 4, 0, 0), null, 1_000);

        assertThat(page.transactions()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("일괄 조회는 키 목록을 본문에 담아 POST로 보낸다")
    void lookup() {
        server.expect(requestTo("http://card/v1/transactions/lookup"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"transactionKeys":["tx_1","tx_2"]}
                        """))
                .andRespond(withSuccess("""
                        {"transactions":[{"transactionKey":"tx_1","type":"CANCEL","amount":3000,"status":"REJECTED"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.lookup(List.of("tx_1", "tx_2")))
                .containsExactly(new CardTransactionRecord("tx_1", "CANCEL", 3_000, "REJECTED"));
        server.verify();
    }

    @Test
    @DisplayName("거래 목록 조회 중 통신에 실패하면 CardCompanyUnavailableException")
    void listTransactionsUnavailable() {
        server.expect(requestTo("http://card/v1/transactions?from=2026-11-03T00:00&to=2026-11-04T00:00&limit=1000"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.listTransactions(
                LocalDateTime.of(2026, 11, 3, 0, 0), LocalDateTime.of(2026, 11, 4, 0, 0), null, 1_000))
                .isInstanceOf(CardCompanyUnavailableException.class);
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
