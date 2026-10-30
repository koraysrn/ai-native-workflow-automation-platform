package com.awap.engine.infrastructure;

import com.awap.engine.domain.SagaStep;
import com.awap.engine.domain.SagaStepStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped data access for saga steps. */
public interface SagaStepRepository extends JpaRepository<SagaStep, UUID> {

  Optional<SagaStep> findBySagaInstanceIdAndId(UUID sagaInstanceId, UUID id);

  Optional<SagaStep> findBySagaInstanceIdAndIdempotencyKey(
      UUID sagaInstanceId, String idempotencyKey);

  List<SagaStep> findBySagaInstanceIdOrderByStepOrderAsc(UUID sagaInstanceId);

  List<SagaStep> findBySagaInstanceIdOrderByStepOrderDesc(UUID sagaInstanceId);

  long countBySagaInstanceId(UUID sagaInstanceId);

  long countBySagaInstanceIdAndStatus(UUID sagaInstanceId, SagaStepStatus status);
}
