package com.awap.engine.infrastructure;

import com.awap.engine.domain.SagaInstance;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped data access for saga coordinator rows. */
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

  Optional<SagaInstance> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

  Optional<SagaInstance> findByTenantIdAndWorkflowInstanceIdAndDeletedFalse(
      UUID tenantId, UUID workflowInstanceId);

  Optional<SagaInstance> findByTenantIdAndSagaKeyAndDeletedFalse(UUID tenantId, String sagaKey);
}
