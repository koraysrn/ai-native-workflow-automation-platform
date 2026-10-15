package com.awap.identity.infrastructure;

import com.awap.identity.domain.IdempotencyRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for idempotency records. */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

  java.util.Optional<IdempotencyRecord> findByTenantIdAndIdempotencyKey(
      UUID tenantId, String idempotencyKey);
}
