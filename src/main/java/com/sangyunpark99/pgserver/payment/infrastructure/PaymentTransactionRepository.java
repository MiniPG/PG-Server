package com.sangyunpark99.pgserver.payment.infrastructure;

import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByPaymentKeyAndTypeOrderByIdAsc(String paymentKey, TransactionType type);

    @Query("select t from PaymentTransaction t where t.mId = :mId and t.transactionKey = :transactionKey")
    Optional<PaymentTransaction> findByMerchantAndTransactionKey(@Param("mId") String mId,
                                                                 @Param("transactionKey") String transactionKey);

    long countByStatus(TransactionStatus status);

    // 정렬·커서 기준을 인덱스 (m_id, transacted_at) 순서와 맞춘다 (id는 보조 인덱스 끝에 붙는 PK)
    // → 정렬 없이 인덱스에서 커서 위치로 바로 가서 limit만큼만 읽는다
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
