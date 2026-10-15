package com.awap.workflow.infrastructure;

import com.awap.workflow.domain.WorkflowVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for workflow version snapshots. */
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

  Optional<WorkflowVersion> findTopByWorkflowIdOrderByVersionDesc(UUID workflowId);

  Optional<WorkflowVersion> findByWorkflowIdAndVersion(UUID workflowId, int version);

  List<WorkflowVersion> findByWorkflowIdOrderByVersionDesc(UUID workflowId);
}
