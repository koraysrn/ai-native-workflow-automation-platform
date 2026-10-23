package com.awap.engine.infrastructure;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for node executions. */
public interface NodeExecutionRepository extends JpaRepository<NodeExecution, UUID> {

  Optional<NodeExecution> findByWorkflowInstanceIdAndNodeId(UUID workflowInstanceId, String nodeId);

  List<NodeExecution> findByWorkflowInstanceIdOrderByCreatedAtAsc(UUID workflowInstanceId);

  List<NodeExecution> findByWorkflowInstanceIdAndStatus(
      UUID workflowInstanceId, NodeExecutionStatus status);

  List<NodeExecution> findByResumeAtNotNullAndResumeAtBeforeOrderByResumeAtAsc(Instant now);
}
