package com.sangyunpark99.pgserver.card;

import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardClientRetryTest {

    private HttpServer server;
    private CloseableHttpClient httpClient;
    private CardCompanyClient client;
    private final AtomicInteger received = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/approvals", exchange -> {
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        CardClientProperties properties = new CardClientProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1), Duration.ofSeconds(3), 10);
        CardClientConfig config = new CardClientConfig();
        httpClient = config.cardHttpClient(properties);
        client = config.httpCardCompanyClient(config.cardRestClient(RestClient.builder(), httpClient, properties));
    }

    @AfterEach
    void tearDown() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    @Test
    @DisplayName("카드사가 503을 주면 HTTP 클라이언트가 몰래 다시 보내지 않고 한 번만 보낸 뒤 바로 실패한다")
    void noAutomaticRetryOn503() {
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> client.approve("tx_retry", "pk_retry", 10_000))
                .isInstanceOf(CardCompanyUnavailableException.class);

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[재시도] 카드사가 받은 요청 %d번, 걸린 시간 %dms%n", received.get(), elapsed);
        assertThat(received.get()).isEqualTo(1);
        assertThat(elapsed).isLessThan(500);
    }
}
