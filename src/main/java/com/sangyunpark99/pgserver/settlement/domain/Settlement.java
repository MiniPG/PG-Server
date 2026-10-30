package com.sangyunpark99.pgserver.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "settlement",
        uniqueConstraints = @UniqueConstraint(name = "uk_settlement_mid_date", columnNames = {"m_id", "settlement_date"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "m_id", nullable = false, length = 14)
    private String mId;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "approve_amount", nullable = false)
    private long approveAmount;

    @Column(name = "cancel_amount", nullable = false)
    private long cancelAmount;

    @Column(name = "approve_count", nullable = false)
    private long approveCount;

    @Column(name = "cancel_count", nullable = false)
    private long cancelCount;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    @Column(name = "fee", nullable = false)
    private long fee;

    @Column(name = "payout_amount", nullable = false)
    private long payoutAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Settlement create(String mId, LocalDate settlementDate,
                                    long approveAmount, long cancelAmount,
                                    long approveCount, long cancelCount,
                                    BigDecimal feeRate, LocalDateTime now) {
        Settlement s = new Settlement();
        s.mId = mId;
        s.settlementDate = settlementDate;
        s.approveAmount = approveAmount;
        s.cancelAmount = cancelAmount;
        s.approveCount = approveCount;
        s.cancelCount = cancelCount;
        s.netAmount = approveAmount - cancelAmount;
        s.fee = calculateFee(s.netAmount, feeRate);
        s.payoutAmount = s.netAmount - s.fee;
        s.createdAt = now;
        return s;
    }

    static long calculateFee(long netAmount, BigDecimal feeRate) {
        return BigDecimal.valueOf(netAmount)
                .multiply(feeRate)
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
    }
}
