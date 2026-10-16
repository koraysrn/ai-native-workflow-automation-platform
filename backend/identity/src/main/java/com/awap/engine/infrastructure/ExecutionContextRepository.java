package com.awap.engine.infrastructure;

import com.awap.engine.domain.ExecutionContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for execution contexts. */
public interface ExecutionContextRepository extends JpaRepository<ExecutionContext, UUID> {

  Optional<ExecutionContext> findByWorkflowInstanceId(UUID workflowInstanceId);
}
