package com.awap.workflow.infrastructure;

import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for workflow node definitions. */
public interface NodeDefinitionRepository extends JpaRepository<NodeDefinition, UUID> {

  List<NodeDefinition> findByWorkflowVersionIdOrderByOrderIndexAsc(UUID workflowVersionId);

  Optional<NodeDefinition> findByWorkflowVersionIdAndNodeId(UUID workflowVersionId, String nodeId);

  Optional<NodeDefinition> findFirstByWorkflowVersionIdAndNodeType(
      UUID workflowVersionId, NodeType nodeType);
}
