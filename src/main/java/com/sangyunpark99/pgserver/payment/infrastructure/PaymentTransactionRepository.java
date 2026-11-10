package com.sangyunpark99.pgserver.payment.infrastructure;

import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByPaymentKeyAndTypeOrderByIdAsc(String paymentKey, TransactionType type);

    @Query("select t from PaymentTransaction t where t.mId = :mId and t.transactionKey = :transactionKey")
    Optional<PaymentTransaction> findByMerchantAndTransactionKey(@Param("mId") String mId,
                                                                 @Param("transactionKey") String transactionKey);

    long countByStatus(TransactionStatus status);

    long countByStatusAndTransactedAtBefore(TransactionStatus status, LocalDateTime before);

    List<PaymentTransaction> findByTransactionKeyIn(Collection<String> transactionKeys);

    @Query("""
            select t from PaymentTransaction t
            where t.transactedAt >= :from and t.transactedAt < :to
            order by t.transactedAt asc, t.id asc
            """)
    List<PaymentTransaction> findReconciliationFirstPage(@Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to,
                                                         Pageable pageable);

    @Query("""
            select t from PaymentTransaction t
            where (t.transactedAt > :afterAt or (t.transactedAt = :afterAt and t.id > :afterId))
              and t.transactedAt >= :from and t.transactedAt < :to
            order by t.transactedAt asc, t.id asc
            """)
    List<PaymentTransaction> findReconciliationPageAfter(@Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to,
                                                         @Param("afterAt") LocalDateTime afterAt,
                                                         @Param("afterId") long afterId,
                                                         Pageable pageable);

    @Query(value = """
            select id from payment_transaction
            where status = 'REQUESTED'
              and transacted_at < :deadline
              and (claimed_until is null or claimed_until < :now)
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Long> findClaimableIds(@Param("deadline") LocalDateTime deadline,
                                @Param("now") LocalDateTime now,
                                @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PaymentTransaction t set t.claimedUntil = :claimedUntil where t.id in :ids")
    int claim(@Param("ids") Collection<Long> ids, @Param("claimedUntil") LocalDateTime claimedUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PaymentTransaction t set t.claimedUntil = null where t.id = :id")
    int releaseClaim(@Param("id") Long id);

    List<PaymentTransaction> findByIdInOrderByIdAsc(Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentTransaction t
            set t.status = :next, t.completedAt = :completedAt
            where t.id = :id
              and t.status = com.sangyunpark99.pgserver.payment.domain.TransactionStatus.REQUESTED
            """)
    int finishIfRequested(@Param("id") Long id,
                          @Param("next") TransactionStatus next,
                          @Param("completedAt") LocalDateTime completedAt);

    @Query("""
            select t from PaymentTransaction t
            where t.mId = :mId
              and t.transactedAt >= :startDate
              and t.transactedAt <= :endDate
            order by t.transactedAt asc, t.id asc
            """)
    List<PaymentTransaction> findFirstPage(@Param("mId") String mId,
                                           @Param("startDate") LocalDateTime startDate,
                                           @Param("endDate") LocalDateTime endDate,
                                           Pageable pageable);

    @Query("""
            select t from PaymentTransaction t
            where t.mId = :mId
              and (t.transactedAt > :afterAt or (t.transactedAt = :afterAt and t.id > :afterId))
              and t.transactedAt >= :startDate
              and t.transactedAt <= :endDate
            order by t.transactedAt asc, t.id asc
            """)
    List<PaymentTransaction> findPageAfter(@Param("mId") String mId,
                                           @Param("startDate") LocalDateTime startDate,
                                           @Param("endDate") LocalDateTime endDate,
                                           @Param("afterAt") LocalDateTime afterAt,
                                           @Param("afterId") long afterId,
                                           Pageable pageable);

    @Query("""
            select new com.sangyunpark99.pgserver.payment.infrastructure.MerchantTransactionSummary(
                t.mId,
                sum(case when t.type = com.sangyunpark99.pgserver.payment.domain.TransactionType.APPROVE then t.amount else 0 end),
                sum(case when t.type = com.sangyunpark99.pgserver.payment.domain.TransactionType.CANCEL then t.amount else 0 end),
                sum(case when t.type = com.sangyunpark99.pgserver.payment.domain.TransactionType.APPROVE then 1L else 0L end),
                sum(case when t.type = com.sangyunpark99.pgserver.payment.domain.TransactionType.CANCEL then 1L else 0L end))
            from PaymentTransaction t
            where t.status = com.sangyunpark99.pgserver.payment.domain.TransactionStatus.DONE
              and t.completedAt >= :start
              and t.completedAt < :end
            group by t.mId
            """)
    List<MerchantTransactionSummary> summarizeCompletedByMerchant(@Param("start") LocalDateTime start,
                                                                  @Param("end") LocalDateTime end);
}
