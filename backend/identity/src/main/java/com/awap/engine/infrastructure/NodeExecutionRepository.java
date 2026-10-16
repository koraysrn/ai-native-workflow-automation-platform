package com.awap.engine.infrastructure;

import com.awap.engine.domain.NodeExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for node executions. */
public interface NodeExecutionRepository extends JpaRepository<NodeExecution, UUID> {

  Optional<NodeExecution> findByWorkflowInstanceIdAndNodeId(UUID workflowInstanceId, String nodeId);

  List<NodeExecution> findByWorkflowInstanceIdOrderByCreatedAtAsc(UUID workflowInstanceId);
}
