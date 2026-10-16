package com.awap.workflow.infrastructure;

import com.awap.workflow.domain.WorkflowVariable;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for workflow variable declarations. */
public interface WorkflowVariableRepository extends JpaRepository<WorkflowVariable, UUID> {

  List<WorkflowVariable> findByWorkflowVersionId(UUID workflowVersionId);
}
