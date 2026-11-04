package com.sangyunpark99.pgserver.payment.infrastructure;

import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentKey(String paymentKey);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForShare(@Param("id") Long id);

    @Query("select p from Payment p where p.mId = :mId and p.paymentKey = :paymentKey")
    Optional<Payment> findByMerchantAndPaymentKey(@Param("mId") String mId, @Param("paymentKey") String paymentKey);

    @Query("select p from Payment p where p.mId = :mId and p.orderId = :orderId")
    Optional<Payment> findByMerchantAndOrderId(@Param("mId") String mId, @Param("orderId") String orderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
            set p.status = com.sangyunpark99.pgserver.payment.domain.PaymentStatus.EXPIRED
            where p.status = com.sangyunpark99.pgserver.payment.domain.PaymentStatus.IN_PROGRESS
              and p.requestedAt < :deadline
            """)
    int expireOverdue(@Param("deadline") LocalDateTime deadline);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payment p set p.status = :next where p.id = :id and p.status = :current")
    int changeStatusIf(@Param("id") Long id,
                       @Param("current") PaymentStatus current,
                       @Param("next") PaymentStatus next);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
            set p.status = case when p.balanceAmount = :amount
                                then com.sangyunpark99.pgserver.payment.domain.PaymentStatus.CANCELED
                                else com.sangyunpark99.pgserver.payment.domain.PaymentStatus.PARTIAL_CANCELED end,
                p.balanceAmount = p.balanceAmount - :amount
            where p.id = :id
              and p.status in (com.sangyunpark99.pgserver.payment.domain.PaymentStatus.DONE, com.sangyunpark99.pgserver.payment.domain.PaymentStatus.PARTIAL_CANCELED)
              and p.balanceAmount >= :amount
            """)
    int deductBalance(@Param("id") Long id, @Param("amount") long amount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
            set p.status = case when p.balanceAmount + :amount = p.totalAmount
                                then com.sangyunpark99.pgserver.payment.domain.PaymentStatus.DONE
                                else com.sangyunpark99.pgserver.payment.domain.PaymentStatus.PARTIAL_CANCELED end,
                p.balanceAmount = p.balanceAmount + :amount
            where p.id = :id
            """)
    int restoreBalance(@Param("id") Long id, @Param("amount") long amount);
}
