package com.sangyunpark99.pgserver.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_record", columnNames = {"m_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "m_id", nullable = false, length = 14)
    private String mId;

    @Column(name = "idempotency_key", nullable = false, length = 300)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 20)
    private IdempotentOperation operation;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "payment_key", nullable = false, length = 200)
    private String paymentKey;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static IdempotencyRecord of(String mId, String idempotencyKey, IdempotentOperation operation,
                                       String requestFingerprint, String paymentKey, Long transactionId,
                                       LocalDateTime now) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.mId = mId;
        r.idempotencyKey = idempotencyKey;
        r.operation = operation;
        r.requestFingerprint = requestFingerprint;
        r.paymentKey = paymentKey;
        r.transactionId = transactionId;
        r.createdAt = now;
        return r;
    }

    public boolean sameRequest(String requestFingerprint) {
        return this.requestFingerprint.equals(requestFingerprint);
    }

    public boolean hasResponse() {
        return responseBody != null;
    }
}
