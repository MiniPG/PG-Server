package com.sangyunpark99.pgserver.payment.infrastructure;

import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
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

    Optional<PaymentTransaction> findByTransactionKey(String transactionKey);

    @Query("""
            select t from PaymentTransaction t
            where t.mId = :mId
              and t.transactedAt >= :startDate
              and t.transactedAt <= :endDate
              and t.id > :afterId
            order by t.id asc
            """)
    List<PaymentTransaction> findPage(@Param("mId") String mId,
                                      @Param("startDate") LocalDateTime startDate,
                                      @Param("endDate") LocalDateTime endDate,
                                      @Param("afterId") long afterId,
                                      Pageable pageable);
}
