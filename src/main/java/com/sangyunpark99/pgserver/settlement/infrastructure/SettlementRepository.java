package com.sangyunpark99.pgserver.settlement.infrastructure;

import com.sangyunpark99.pgserver.settlement.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    @Query("select s.mId from Settlement s where s.settlementDate = :date")
    Set<String> findSettledMerchantIds(@Param("date") LocalDate date);

    @Query("""
            select s from Settlement s
            where s.mId = :mId and s.settlementDate between :startDate and :endDate
            order by s.settlementDate asc
            """)
    List<Settlement> findByMerchantAndPeriod(@Param("mId") String mId,
                                             @Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);
}
