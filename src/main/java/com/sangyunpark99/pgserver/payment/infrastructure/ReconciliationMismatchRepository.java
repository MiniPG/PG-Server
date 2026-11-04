package com.sangyunpark99.pgserver.payment.infrastructure;

import com.sangyunpark99.pgserver.payment.domain.ReconciliationMismatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ReconciliationMismatchRepository extends JpaRepository<ReconciliationMismatch, Long> {

    List<ReconciliationMismatch> findByBusinessDateOrderByIdAsc(LocalDate businessDate);

    @Modifying
    @Query("delete from ReconciliationMismatch m where m.businessDate = :businessDate")
    int deleteByBusinessDate(@Param("businessDate") LocalDate businessDate);
}
