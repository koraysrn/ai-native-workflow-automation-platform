package com.awap.workflow.infrastructure;

import com.awap.workflow.domain.WorkflowDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for workflow definitions. */
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

  List<WorkflowDefinition> findByTenantIdAndDeletedFalse(UUID tenantId);

  Optional<WorkflowDefinition> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

  long countByTenantIdAndDeletedFalse(UUID tenantId);
}
