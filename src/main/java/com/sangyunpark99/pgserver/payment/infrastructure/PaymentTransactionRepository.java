package com.sangyunpark99.pgserver.payment.infrastructure;

import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByPaymentKey(String paymentKey);
}
