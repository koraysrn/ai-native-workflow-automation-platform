package com.awap.engine.infrastructure;

import com.awap.engine.domain.WorkflowInstance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for workflow instances. */
public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

  List<WorkflowInstance> findByTenantIdAndDeletedFalseOrderByCreatedAtDesc(UUID tenantId);

  Optional<WorkflowInstance> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

  Optional<WorkflowInstance> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
}
