package com.sangyunpark99.pgserver.experiment;

import com.sangyunpark99.pgserver.common.time.TimeProvider;
import com.sangyunpark99.pgserver.payment.application.expiration.PaymentExpirationService;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
@EnabledIfEnvironmentVariable(named = "PG_EXPERIMENT", matches = "expiration-deadlock")
class ExpirationDeadlockExperimentTest {

    private static final String M_ID = "m_exp_deadlock";
    private static final int APPROVERS = 8;
    private static final Duration RUN = Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("RUN_SECONDS", "20")));
    private static final boolean RANGE_UPDATE = "range".equals(System.getenv("EXPIRATION"));
    private static final String RANGE_EXPIRE_SQL = "update payment set status = 'EXPIRED' where status = 'IN_PROGRESS' and requested_at < ?";

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withUsername("root")
            .withPassword("test");

    static {
        if ("expiration-deadlock".equals(System.getenv("PG_EXPERIMENT"))) {
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
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentExpirationService expirationService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private TimeProvider timeProvider;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    @Test
    void run() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger approveDeadlocks = new AtomicInteger();
        AtomicInteger expireRuns = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger expireDeadlocks = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(APPROVERS + 1);
        pool.submit(() -> {
            while (running.get()) {
                try {
                    expired.addAndGet(transactionTemplate.execute(status -> expire()));
                    expireRuns.incrementAndGet();
                } catch (PessimisticLockingFailureException e) {
                    expireDeadlocks.incrementAndGet();
                }
            }
        });
        for (int i = 0; i < APPROVERS; i++) {
            pool.submit(() -> {
                int n = 0;
                while (running.get()) {
                    LocalDateTime requestedAt = n++ % 2 == 0 ? timeProvider.now() : overdue();
                    Long id = transactionTemplate.execute(status -> paymentRepository.save(newPayment(requestedAt)).getId());
                    try {
                        transactionTemplate.executeWithoutResult(status -> {
                            if (paymentRepository.changeStatusIf(id, PaymentStatus.IN_PROGRESS, PaymentStatus.APPROVING) == 1) {
                                claimed.incrementAndGet();
                            }
                        });
                    } catch (PessimisticLockingFailureException e) {
                        approveDeadlocks.incrementAndGet();
                    }
                }
            });
        }

        Thread.sleep(RUN.toMillis());
        running.set(false);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        String status;
        try {
            status = jdbc.queryForMap("SHOW ENGINE INNODB STATUS").get("Status").toString();
        } catch (RuntimeException e) {
            status = "SHOW ENGINE INNODB STATUS 실패: " + e.getMessage();
        }
        int from = status.indexOf("LATEST DETECTED DEADLOCK");
        int to = status.indexOf("TRANSACTIONS\n------------", from);
        String deadlock = from < 0 ? "(데드락 기록 없음)" : status.substring(from, to < 0 ? status.length() : to);

        String report = """
                만료 방식: %s
                실행 시간: %d초, 승인 스레드: %d개
                승인 선점 성공: %d, 승인 데드락: %d
                만료 실행: %d, 만료된 결제: %d, 만료 데드락: %d
                isolation: %s

                %s
                """.formatted(mode(), RUN.toSeconds(), APPROVERS, claimed.get(), approveDeadlocks.get(),
                expireRuns.get(), expired.get(), expireDeadlocks.get(),
                jdbc.queryForObject("select @@transaction_isolation", String.class), deadlock);
        System.out.println(report);
        Path out = Path.of("build/experiment/expiration-deadlock.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    @Test
    void locksTakenByExpiration() throws Exception {
        jdbc.update("delete from payment");
        for (int i = 0; i < 3; i++) {
            Long id = transactionTemplate.execute(status -> paymentRepository.save(newPayment()).getId());
            transactionTemplate.executeWithoutResult(status ->
                    paymentRepository.changeStatusIf(id, PaymentStatus.IN_PROGRESS, PaymentStatus.APPROVING));
        }
        for (int i = 0; i < 5; i++) {
            transactionTemplate.executeWithoutResult(status -> paymentRepository.save(newPayment()));
        }
        for (int i = 0; i < 2; i++) {
            transactionTemplate.executeWithoutResult(status -> paymentRepository.save(newPayment(overdue())));
        }
        LocalDateTime deadline = timeProvider.now().minus(Payment.VALID_DURATION);

        String rows = jdbc.queryForList("select id, status, requested_at from payment order by status, requested_at, id")
                .stream().map(Map::toString).collect(Collectors.joining("\n"));
        String explain = query("explain format=tree " + RANGE_EXPIRE_SQL, deadline);
        String explainTable = query("explain " + RANGE_EXPIRE_SQL, deadline);

        String locks = transactionTemplate.execute(status -> {
            int expired = expire();
            List<Map<String, Object>> held = jdbc.queryForList("""
                    select index_name, lock_mode, lock_status, lock_data
                    from performance_schema.data_locks
                    where object_name = 'payment' and lock_type = 'RECORD'
                    order by index_name, lock_data
                    """);
            return "만료된 행: " + expired + "\n" + held.stream().map(Map::toString).collect(Collectors.joining("\n"));
        });

        String report = """
                만료 방식: %s
                deadline: %s

                [payment 행 (status, requested_at 순)]
                %s

                [EXPLAIN]
                %s

                [EXPLAIN FORMAT=TREE]
                %s

                [만료 쿼리가 쥔 락]
                %s
                """.formatted(mode(), deadline, rows, explainTable, explain, locks);
        System.out.println(report);
        Path out = Path.of("build/experiment/expiration-locks.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    @Test
    void locksWithUnpurgedRecords() throws Exception {
        jdbc.update("delete from payment");
        List<Long> toApprove = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            toApprove.add(transactionTemplate.execute(status -> paymentRepository.save(newPayment()).getId()));
        }
        for (int i = 0; i < 5; i++) {
            transactionTemplate.executeWithoutResult(status -> paymentRepository.save(newPayment()));
        }

        String locks;
        String history;
        try (Connection snapshot = dataSource.getConnection(); Statement st = snapshot.createStatement()) {
            st.execute("start transaction with consistent snapshot");

            for (Long id : toApprove) {
                transactionTemplate.executeWithoutResult(status ->
                        paymentRepository.changeStatusIf(id, PaymentStatus.IN_PROGRESS, PaymentStatus.APPROVING));
            }
            history = query("select name, count from information_schema.innodb_metrics where name = 'trx_rseg_history_len'");

            LocalDateTime deadline = timeProvider.now().minus(Payment.VALID_DURATION);
            locks = transactionTemplate.execute(status -> {
                int expired = expire();
                return "만료된 행: " + expired + "\n" + query("""
                        select index_name, lock_mode, lock_status, lock_data
                        from performance_schema.data_locks
                        where object_name = 'payment' and lock_type = 'RECORD'
                        order by index_name, lock_data
                        """);
            });
            st.execute("rollback");
        }

        String rows = query("select id, status, requested_at from payment order by status, requested_at, id");
        String report = """
                만료 방식: %s
                [오래된 읽기 트랜잭션을 열어 둔 채 1~3번을 승인 → purge가 옛 인덱스 레코드를 못 지움]

                [payment 행 (status, requested_at 순)]
                %s

                [purge 대기 (history list length)]
                %s

                [만료 쿼리가 쥔 락]
                %s
                """.formatted(mode(), rows, history, locks);
        System.out.println(report);
        Path out = Path.of("build/experiment/expiration-locks-unpurged.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    private String query(String sql, Object... args) {
        try {
            return jdbc.queryForList(sql, args).stream().map(Map::toString).collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return "실패: " + e.getMessage();
        }
    }

    private int expire() {
        LocalDateTime now = timeProvider.now();
        if (RANGE_UPDATE) {
            return jdbc.update(RANGE_EXPIRE_SQL, now.minus(Payment.VALID_DURATION));
        }
        return expirationService.expireOverdue(now);
    }

    private String mode() {
        return RANGE_UPDATE ? "범위 UPDATE (수정 전)" : "id 조회 후 id로 UPDATE (수정 후)";
    }

    private LocalDateTime overdue() {
        return timeProvider.now().minus(Payment.VALID_DURATION).minusMinutes(1);
    }

    private Payment newPayment() {
        return newPayment(timeProvider.now());
    }

    private Payment newPayment(LocalDateTime requestedAt) {
        String id = UUID.randomUUID().toString();
        return Payment.create("pk_" + id.replace("-", ""), "order-" + id, "deadlock", M_ID, 10_000, true, requestedAt);
    }
}
