package com.sangyunpark99.pgserver.experiment;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.support.TestMerchants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PG_EXPERIMENT", matches = "query-count")
class QueryCountExperimentTest {

    private static final String MERCHANT = "m_query_count";
    private static final int REQUESTS = 100;

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withUsername("root")
            .withPassword("test");

    static {
        if ("query-count".equals(System.getenv("PG_EXPERIMENT"))) {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestMerchants merchants;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private CardCompanyClient cardCompanyClient;

    @Test
    void run() throws Exception {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.approved("12345678", "11", "1234-****-****-5678"));
        given(cardCompanyClient.cancel(anyString(), anyString(), anyLong()))
                .willReturn(CardCancelResult.success());
        String auth = merchants.authorization(MERCHANT);

        List<String[]> created = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("요청 ").append(REQUESTS).append("번씩, 요청 1번당 평균 쿼리 수\n\n");

        report.append(measure("생성 POST /v1/payments", () -> {
            for (int i = 0; i < REQUESTS; i++) {
                String orderId = "order-" + UUID.randomUUID();
                String body = mockMvc.perform(post("/v1/payments").header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"orderId\":\"" + orderId + "\",\"orderName\":\"티셔츠\",\"amount\":10000}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                String paymentKey = JsonPath.read(body, "$.paymentKey");
                created.add(new String[]{paymentKey, orderId});
            }
        }));

        report.append(measure("승인 POST /v1/payments/confirm", () -> {
            for (String[] p : created) {
                mockMvc.perform(post("/v1/payments/confirm").header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"paymentKey\":\"" + p[0] + "\",\"orderId\":\"" + p[1] + "\",\"amount\":10000}"))
                        .andExpect(status().isOk());
            }
        }));

        report.append(measure("조회 GET /v1/payments/{paymentKey}", () -> {
            for (String[] p : created) {
                mockMvc.perform(get("/v1/payments/{key}", p[0]).header("Authorization", auth))
                        .andExpect(status().isOk());
            }
        }));

        report.append(measure("취소 POST /v1/payments/{paymentKey}/cancel", () -> {
            for (String[] p : created) {
                mockMvc.perform(post("/v1/payments/{key}/cancel", p[0]).header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cancelReason\":\"단순 변심\"}"))
                        .andExpect(status().isOk());
            }
        }));

        System.out.println(report);
        Path out = Path.of("build/experiment/query-count.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }

    private String measure(String title, Requests requests) throws Exception {
        jdbc.update("TRUNCATE performance_schema.events_statements_summary_by_digest");
        requests.run();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select COUNT_STAR as cnt, LEFT(DIGEST_TEXT, 90) as q
                from performance_schema.events_statements_summary_by_digest
                where SCHEMA_NAME = 'test'
                  and DIGEST_TEXT not like 'TRUNCATE%'
                  and DIGEST_TEXT not like '%performance_schema%'
                order by COUNT_STAR desc
                """);
        long total = rows.stream().mapToLong(r -> ((Number) r.get("cnt")).longValue()).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(title).append("] 합계 ").append(String.format("%.1f", (double) total / REQUESTS)).append("\n");
        for (Map<String, Object> r : rows) {
            sb.append(String.format("  %5.1f  %s%n", ((Number) r.get("cnt")).doubleValue() / REQUESTS, r.get("q")));
        }
        return sb.append("\n").toString();
    }

    @FunctionalInterface
    private interface Requests {
        void run() throws Exception;
    }
}
