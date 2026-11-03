package com.sangyunpark99.pgserver.experiment;

import com.sangyunpark99.pgserver.common.time.TimeProvider;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.MerchantTransactionSummary;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/pg?serverTimezone=Asia/Seoul",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none"
})
@EnabledIfEnvironmentVariable(named = "PG_EXPERIMENT", matches = "datetime-precision")
class DatetimePrecisionExperimentTest {

    private static final String M_ID = "m_exp_precise";
    private static final LocalDateTime NANOS = LocalDateTime.of(2026, 11, 1, 23, 59, 59, 999_999_600);
    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 1);

    @Autowired
    private PaymentTransactionRepository transactionRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> out = new ArrayList<>();
    private final List<String> txKeys = new ArrayList<>();

    @Test
    void run() throws IOException {
        try {
            line("MySQL", jdbc.queryForObject("select version()", String.class));
            line("sql_mode", jdbc.queryForObject("select @@session.sql_mode", String.class));
            line("Connector/J", jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDriverVersion()));
            line("입력 값 (Java)", NANOS.toString());
            out.add("");

            jdbc.execute("drop table if exists exp_datetime_precision");
            jdbc.execute("create table exp_datetime_precision (name varchar(40), v datetime(6))");

            jdbc.update("insert into exp_datetime_precision values ('1. SQL 리터럴', '2026-11-01 23:59:59.9999996')");
            jdbc.update("insert into exp_datetime_precision values ('2. JDBC setObject(LocalDateTime)', ?)", NANOS);
            jdbc.update("insert into exp_datetime_precision values ('3. JDBC truncatedTo(MICROS)', ?)", NANOS.truncatedTo(ChronoUnit.MICROS));
            jdbc.queryForList("select name, cast(v as char(26)) as v from exp_datetime_precision")
                    .forEach(r -> line((String) r.get("name"), (String) r.get("v")));

            PaymentTransaction raw = saveDone(NANOS);
            PaymentTransaction truncated = saveDone(NANOS.truncatedTo(ChronoUnit.MICROS));
            line("4. JPA 저장 후 엔티티 값 (응답에 쓰이는 값)", raw.getCompletedAt().toString());
            line("5. JPA 저장 후 DB 값", dbValue(raw));
            line("6. JPA truncatedTo(MICROS) DB 값", dbValue(truncated));
            ZoneId zone = ZoneId.systemDefault();
            LocalDateTime fromProvider = new TimeProvider(Clock.fixed(NANOS.atZone(zone).toInstant(), zone)).now();
            PaymentTransaction viaProvider = saveDone(fromProvider);
            line("7. TimeProvider 엔티티 값 (응답에 쓰이는 값)", viaProvider.getCompletedAt().toString());
            line("8. TimeProvider DB 값", dbValue(viaProvider));
            out.add("");

            line("정산 11/01 (DONE 승인 건수, 이 가맹점)", count(DAY1));
            line("정산 11/02 (DONE 승인 건수, 이 가맹점)", count(DAY1.plusDays(1)));
        } finally {
            txKeys.forEach(k -> jdbc.update("delete from payment_transaction where transaction_key = ?", k));
            jdbc.execute("drop table if exists exp_datetime_precision");
            Path file = Path.of("build/experiments/datetime-precision-result.txt");
            Files.createDirectories(file.getParent());
            Files.write(file, out);
        }
    }

    private PaymentTransaction saveDone(LocalDateTime at) {
        String key = "tx_exp_" + UUID.randomUUID();
        txKeys.add(key);
        PaymentTransaction tx = PaymentTransaction.request(key, "pay_exp_" + UUID.randomUUID(), M_ID,
                TransactionType.APPROVE, 10_000, null, at);
        tx.complete(at);
        return transactionRepository.saveAndFlush(tx);
    }

    private String dbValue(PaymentTransaction tx) {
        return jdbc.queryForObject("select cast(completed_at as char(26)) from payment_transaction where transaction_key = ?",
                String.class, tx.getTransactionKey());
    }

    private String count(LocalDate date) {
        return transactionRepository.summarizeCompletedByMerchant(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                .stream().filter(s -> s.mId().equals(M_ID))
                .map(MerchantTransactionSummary::approveCount)
                .findFirst().map(v -> Long.toString(v)).orElse("0");
    }

    private void line(String name, String value) {
        out.add(String.format("%-45s %s", name, value));
    }
}
