package com.sangyunpark99.pgserver.payment.transaction;

import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import jakarta.persistence.TransactionRequiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AutoCommitOffTest {

    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentTransactionRepository transactionRepository;

    @Test
    @DisplayName("풀에서 꺼낸 커넥션은 실제로 autocommit이 꺼져 있다 (두 설정이 어긋나지 않았다)")
    void connectionIsNotAutoCommit() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isFalse();
        }
    }

    @Test
    @DisplayName("트랜잭션 안에서 예외가 나면 앞에서 한 쓰기까지 모두 되돌려진다")
    void rollbackWholeTransaction() {
        String paymentKey = "pk_rollback_" + UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            paymentRepository.save(Payment.create(paymentKey, "order-" + UUID.randomUUID(), "티셔츠",
                    "m_rollback", 10_000, true, LocalDateTime.now()));
            paymentRepository.flush();
            throw new IllegalStateException("중간 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(paymentRepository.findByPaymentKey(paymentKey)).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 없이 저장해도 save가 자기 트랜잭션으로 커밋한다")
    void saveWithoutTransactionIsCommitted() {
        String paymentKey = "pk_save_" + UUID.randomUUID();

        paymentRepository.save(Payment.create(paymentKey, "order-" + UUID.randomUUID(), "티셔츠",
                "m_save", 10_000, true, LocalDateTime.now()));

        assertThat(paymentRepository.findByPaymentKey(paymentKey)).isPresent();
    }

    @Test
    @DisplayName("@Modifying 쿼리를 트랜잭션 없이 부르면 조용히 롤백되지 않고 예외가 난다")
    void modifyingQueryWithoutTransactionFails() {
        assertThatThrownBy(() -> transactionRepository.releaseClaim(1L))
                .hasRootCauseInstanceOf(TransactionRequiredException.class);
    }
}
