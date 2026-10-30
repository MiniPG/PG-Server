package com.sangyunpark99.pgserver.payment.infrastructure;

import com.sangyunpark99.pgserver.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentKey(String paymentKey);

    @Query("select p from Payment p where p.mId = :mId and p.orderId = :orderId")
    Optional<Payment> findByMerchantAndOrderId(@Param("mId") String mId, @Param("orderId") String orderId);
}
