package com.sangyunpark99.pgserver.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "payment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_payment_key", columnNames = "payment_key"),
                @UniqueConstraint(name = "uk_payment_mid_order_id", columnNames = {"m_id", "order_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    public static final Duration VALID_DURATION = Duration.ofMinutes(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", nullable = false, length = 200)
    private String paymentKey;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "order_name", length = 100)
    private String orderName;

    @Column(name = "m_id", nullable = false, length = 14)
    private String mId;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    /** 취소할 수 있는 남은 금액. 부분 취소를 대비해 둔다. */
    @Column(name = "balance_amount", nullable = false)
    private long balanceAmount;

    @Column(name = "is_partial_cancelable", nullable = false)
    private boolean partialCancelable;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Embedded
    private CardInfo card;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_message", length = 510)
    private String failureMessage;

    /** 결제 생성 현 프로젝트에서는 인증까지 끝난 것으로 보고 IN_PROGRESS로 시작한다. */
    public static Payment create(String paymentKey, String orderId, String orderName,
                                 String mId, long totalAmount, LocalDateTime requestedAt) {
        if (totalAmount <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다.");
        }
        Payment p = new Payment();
        p.paymentKey = paymentKey;
        p.orderId = orderId;
        p.orderName = orderName;
        p.mId = mId;
        p.totalAmount = totalAmount;
        p.balanceAmount = totalAmount;
        p.partialCancelable = false;   // 부분 취소는 아직 지원하지 않음
        p.status = PaymentStatus.IN_PROGRESS;
        p.requestedAt = requestedAt;
        return p;
    }

    public void approve(CardInfo card, LocalDateTime approvedAt) {
        changeStatus(PaymentStatus.DONE);
        this.card = card;
        this.approvedAt = approvedAt;
    }

    public void abort(String failureCode, String failureMessage) {
        changeStatus(PaymentStatus.ABORTED);
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    public void expire() {
        changeStatus(PaymentStatus.EXPIRED);
    }

    public void cancel() {
        changeStatus(PaymentStatus.CANCELED);
        this.balanceAmount = 0;
    }

    public boolean isExpired(LocalDateTime now) {
        return status == PaymentStatus.IN_PROGRESS
                && now.isAfter(requestedAt.plus(VALID_DURATION));
    }

    private void changeStatus(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "결제 상태를 " + status + "에서 " + next + "(으)로 바꿀 수 없습니다. paymentKey=" + paymentKey);
        }
        this.status = next;
    }
}
