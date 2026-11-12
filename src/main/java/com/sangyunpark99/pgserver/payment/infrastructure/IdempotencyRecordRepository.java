package com.sangyunpark99.pgserver.payment.infrastructure;

import com.sangyunpark99.pgserver.payment.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    @Query("select r from IdempotencyRecord r where r.mId = :mId and r.idempotencyKey = :key")
    Optional<IdempotencyRecord> findByMerchantAndKey(@Param("mId") String mId, @Param("key") String key);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update IdempotencyRecord r set r.responseBody = :body where r.id = :id")
    int saveResponse(@Param("id") Long id, @Param("body") String body);
}
