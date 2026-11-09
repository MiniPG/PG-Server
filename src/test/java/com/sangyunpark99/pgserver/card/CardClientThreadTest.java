package com.sangyunpark99.pgserver.card;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

class CardClientThreadTest {

    private static final int CALLS = 100;
    private static final String APPROVED = """
            {"transactionKey":"tx","approved":true,"approveNo":"00000001","issuerCode":"11","maskedNumber":"1234-****-****-5678"}
            """;

    private HttpServer server;
    private ExecutorService serverThreads;
    private CloseableHttpClient httpClient;
    private CardCompanyClient client;

    @BeforeEach
    void setUp() throws IOException {
        serverThreads = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            serverThreads.submit(() -> { });
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/v1/approvals", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = APPROVED.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        CardClientProperties properties = new CardClientProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1), Duration.ofSeconds(3), 200);
        CardClientConfig config = new CardClientConfig();
        httpClient = config.cardHttpClient(properties);
        client = config.httpCardCompanyClient(config.cardRestClient(RestClient.builder(), httpClient, properties));
    }

    @AfterEach
    void tearDown() throws IOException {
        httpClient.close();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    @DisplayName("카드사 호출마다 스레드를 새로 만들지 않는다 (공용 풀 병렬도 1, vCPU 2개 서버와 같은 조건)")
    void noThreadPerCall() {
        assertThat(ForkJoinPool.getCommonPoolParallelism()).isEqualTo(1);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 20; i++) {
            client.approve("tx_warm_" + i, "pk", 10_000);
        }

        long before = threads.getTotalStartedThreadCount();
        for (int i = 0; i < CALLS; i++) {
            client.approve("tx_" + i, "pk", 10_000);
        }
        long started = threads.getTotalStartedThreadCount() - before;

        assertThat(started).isLessThan(10);
    }
}
