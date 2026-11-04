package com.sangyunpark99.pgserver.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "reconciliation_mismatch",
        indexes = @Index(name = "idx_mismatch_business_date", columnList = "business_date")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationMismatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "transaction_key", nullable = false, length = 64)
    private String transactionKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, length = 20)
    private MismatchType type;

    @Column(name = "pg_status", length = 20)
    private String pgStatus;

    @Column(name = "card_status", length = 20)
    private String cardStatus;

    @Column(name = "pg_amount")
    private Long pgAmount;

    @Column(name = "card_amount")
    private Long cardAmount;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    public static ReconciliationMismatch of(LocalDate businessDate, String transactionKey, MismatchType type,
                                            String pgStatus, String cardStatus, Long pgAmount, Long cardAmount,
                                            LocalDateTime detectedAt) {
        ReconciliationMismatch mismatch = new ReconciliationMismatch();
        mismatch.businessDate = businessDate;
        mismatch.transactionKey = transactionKey;
        mismatch.type = type;
        mismatch.pgStatus = pgStatus;
        mismatch.cardStatus = cardStatus;
        mismatch.pgAmount = pgAmount;
        mismatch.cardAmount = cardAmount;
        mismatch.detectedAt = detectedAt;
        return mismatch;
    }
}
