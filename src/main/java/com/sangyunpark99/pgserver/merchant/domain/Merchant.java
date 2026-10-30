package com.sangyunpark99.pgserver.merchant.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "merchant",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_merchant_mid", columnNames = "m_id"),
                @UniqueConstraint(name = "uk_merchant_secret_key_hash", columnNames = "secret_key_hash")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "m_id", nullable = false, length = 14)
    private String mId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "secret_key_hash", nullable = false, length = 64)
    private String secretKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MerchantStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Merchant create(String mId, String name, String secretKeyHash, LocalDateTime now) {
        Merchant m = new Merchant();
        m.mId = mId;
        m.name = name;
        m.secretKeyHash = secretKeyHash;
        m.status = MerchantStatus.ACTIVE;
        m.createdAt = now;
        return m;
    }

    public void suspend() {
        this.status = MerchantStatus.SUSPENDED;
    }

    public boolean isActive() {
        return status == MerchantStatus.ACTIVE;
    }
}
