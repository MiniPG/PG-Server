package com.sangyunpark99.pgserver.experiment;

import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.application.reconciliation.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "PG_EXPERIMENT", matches = "reconciliation-claim")
class ReconciliationClaimExperimentTest {

    private static final String CLAIM_SQL = """
            select id from payment_transaction
            where status = 'REQUESTED'
              and transacted_at < now() - interval 10 second
              and (claimed_until is null or claimed_until < now())
            order by id
            limit 100
            for update skip locked""";

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withUsername("root")
            .withPassword("test");

    static {
        if ("reconciliation-claim".equals(System.getenv("PG_EXPERIMENT"))) {
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
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private EntityManager entityManager;
    @MockitoBean
    private CardCompanyClient cardCompanyClient;

    @Test
    void isolationInsideTemplate() throws Exception {
        TransactionTemplate readCommitted = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        String inside = readCommitted.execute(status -> describe());
        String after = transactionTemplate.execute(status -> describe());
        write("reconciliation-claim-probe.txt", """
                트랜잭션 매니저: %s
                RC 템플릿 안: %s
                기본 템플릿 안: %s
                """.formatted(transactionTemplate.getTransactionManager().getClass().getName(), inside, after));
    }

    private String describe() {
        Object jpa = entityManager.createNativeQuery("select concat(@@transaction_isolation, ' / connection ', connection_id())").getSingleResult();
        String jdbcSide = jdbc.queryForObject("select concat(@@transaction_isolation, ' / connection ', connection_id())", String.class);
        return "JPA " + jpa + ", JdbcTemplate " + jdbcSide;
    }

    @Test
    void locksByIsolation() throws Exception {
        StringBuilder report = new StringBuilder();
        for (String isolation : List.of("REPEATABLE READ", "READ COMMITTED")) {
            report.append(run(isolation)).append("\n");
        }
        write("reconciliation-claim-locks.txt", report.toString());
    }

    @Test
    void isolationUsedBySpring() throws Exception {
        seed();
        jdbc.execute("truncate table mysql.general_log");
        jdbc.execute("set global log_output = 'TABLE'");
        jdbc.execute("set global general_log = 'ON'");
        reconciliationService.reconcile(LocalDateTime.now());
        jdbc.execute("set global general_log = 'OFF'");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select thread_id, sql_text from (
                    select thread_id, event_time, lower(convert(argument using utf8mb4)) as sql_text
                    from mysql.general_log
                    where command_type = 'Query') q
                where sql_text like '%isolation%' or sql_text like '%skip locked%' or sql_text like '%set claimed_until%'
                   or sql_text = 'commit' or sql_text like 'set autocommit%'
                order by event_time""");
        String text = rows.stream().limit(40)
                .map(r -> r.get("thread_id") + " | " + r.get("sql_text").toString().replaceAll("\\s+", " "))
                .collect(Collectors.joining("\n"));
        write("reconciliation-claim-isolation.txt", "[대사 실행 중 커넥션에 보낸 문장, 앞 40개]\n" + text + "\n");
    }

    private String run(String isolation) throws Exception {
        List<Long> young = seed();
        StringBuilder out = new StringBuilder("===== " + isolation + " =====\n");
        out.append("EXPLAIN: ").append(jdbc.queryForList("explain " + CLAIM_SQL.replace("for update skip locked", ""))).append("\n");

        try (Connection a = begin(isolation); Connection b = begin(isolation); Connection request = begin("REPEATABLE READ")) {
            List<Long> claimedByA = claim(a);
            out.append("A가 집은 행: ").append(claimedByA.size()).append("\n");
            try {
                List<Long> claimedByB = claim(b);
                long overlap = claimedByA.stream().filter(claimedByB::contains).count();
                out.append("B가 집은 행: ").append(claimedByB.size()).append(", A와 겹친 행: ").append(overlap).append("\n");
            } catch (SQLException e) {
                out.append("B 집기 실패: ").append(e.getMessage()).append("\n");
            }

            out.append("A가 쥔 락:\n").append(locksOf(a)).append("\n");

            out.append("요청 경로 - 10초 안 된 거래 확정: ")
                    .append(tryUpdate(request, "update payment_transaction set status = 'DONE' where id = " + young.getFirst() + " and status = 'REQUESTED'"))
                    .append("\n");
            out.append("요청 경로 - 새 거래 INSERT: ")
                    .append(tryUpdate(request, "insert into payment_transaction (transaction_key, payment_key, m_id, type, amount, status, transacted_at) "
                            + "values (concat('exp_new_', uuid()), 'p', 'm', 'APPROVE', 1000, 'REQUESTED', now())"))
                    .append("\n");
            request.rollback();
            b.rollback();
            a.rollback();
        }
        return out.toString();
    }

    private List<Long> seed() {
        jdbc.execute("delete from payment_transaction");
        insert("DONE", "now() - interval 1 day", 1000);
        insert("REQUESTED", "now() - interval 1 hour", 60);
        insert("DONE", "now() - interval 1 day", 1000);
        insert("REQUESTED", "now()", 20);
        jdbc.execute("analyze table payment_transaction");
        return jdbc.queryForList("select id from payment_transaction where status = 'REQUESTED' and transacted_at > now() - interval 1 minute order by id", Long.class);
    }

    private void insert(String status, String transactedAt, int count) {
        jdbc.execute("set session cte_max_recursion_depth = 10000");
        jdbc.update("""
                insert into payment_transaction (transaction_key, payment_key, m_id, type, amount, status, transacted_at)
                with recursive seq(n) as (select 1 union all select n + 1 from seq where n < %d)
                select concat('exp_', uuid()), 'p', 'm', 'APPROVE', 1000, '%s', %s from seq
                """.formatted(count, status, transactedAt));
    }

    private Connection begin(String isolation) throws SQLException {
        Connection c = dataSource.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute("set session transaction isolation level " + isolation);
            s.execute("set session innodb_lock_wait_timeout = 2");
        }
        c.setAutoCommit(false);
        return c;
    }

    private List<Long> claim(Connection c) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(CLAIM_SQL)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        if (!ids.isEmpty()) {
            String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            try (Statement s = c.createStatement()) {
                s.executeUpdate("update payment_transaction set claimed_until = now() + interval 60 second where id in (" + in + ")");
            }
        }
        return ids;
    }

    private String locksOf(Connection c) throws SQLException {
        long trx;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select trx_id from information_schema.innodb_trx where trx_mysql_thread_id = connection_id()")) {
            rs.next();
            trx = rs.getLong(1);
        }
        return jdbc.queryForList("""
                        select index_name, lock_mode, count(*) as cnt,
                               sum(lock_data = 'supremum pseudo-record') as supremum
                        from performance_schema.data_locks
                        where engine_transaction_id = ? and lock_type = 'RECORD'
                        group by index_name, lock_mode
                        order by index_name, lock_mode""", trx)
                .stream().map(r -> "  " + r).collect(Collectors.joining("\n"));
    }

    private String tryUpdate(Connection c, String sql) {
        long start = System.currentTimeMillis();
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
            return "바로 성공 (" + (System.currentTimeMillis() - start) + "ms)";
        } catch (SQLException e) {
            return "막힘 (" + (System.currentTimeMillis() - start) + "ms, " + e.getMessage() + ")";
        }
    }

    private void write(String name, String text) throws Exception {
        System.out.println(text);
        Path out = Path.of("build/experiment/" + name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, text);
    }
}
