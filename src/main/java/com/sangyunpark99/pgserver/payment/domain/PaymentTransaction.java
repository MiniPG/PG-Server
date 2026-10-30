package com.sangyunpark99.pgserver.payment.domain;

import com.sangyunpark99.pgserver.common.exception.ErrorCode;
import com.sangyunpark99.pgserver.common.exception.PgException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "payment_transaction",
        uniqueConstraints = @UniqueConstraint(name = "uk_tx_transaction_key", columnNames = "transaction_key"),
        indexes = {
                @Index(name = "idx_tx_payment_key", columnList = "payment_key"),
                @Index(name = "idx_tx_mid_transacted_at", columnList = "m_id, transacted_at"),
                @Index(name = "idx_tx_status_completed_at", columnList = "status, completed_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_key", nullable = false, length = 64)
    private String transactionKey;

    @Column(name = "payment_key", nullable = false, length = 200)
    private String paymentKey;

    @Column(name = "m_id", nullable = false, length = 14)
    private String mId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private TransactionType type;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "reason", length = 200)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "transacted_at", nullable = false)
    private LocalDateTime transactedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static PaymentTransaction request(String transactionKey, String paymentKey, String mId, TransactionType type,
                                             long amount, String reason, LocalDateTime transactedAt) {
        if (amount <= 0) {
            throw new PgException(ErrorCode.INVALID_AMOUNT);
        }
        PaymentTransaction tx = new PaymentTransaction();
        tx.transactionKey = transactionKey;
        tx.paymentKey = paymentKey;
        tx.mId = mId;
        tx.type = type;
        tx.amount = amount;
        tx.reason = reason;
        tx.status = TransactionStatus.REQUESTED;
        tx.transactedAt = transactedAt;
        return tx;
    }

    public void complete(LocalDateTime completedAt) {
        this.status = TransactionStatus.DONE;
        this.completedAt = completedAt;
    }

    public void fail(LocalDateTime completedAt) {
        this.status = TransactionStatus.FAILED;
        this.completedAt = completedAt;
    }
}
