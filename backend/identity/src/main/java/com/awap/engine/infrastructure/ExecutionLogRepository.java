package com.awap.engine.infrastructure;

import com.awap.engine.domain.ExecutionLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for execution logs. */
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {

  List<ExecutionLog> findByWorkflowInstanceIdOrderByCreatedAtAsc(UUID workflowInstanceId);
}
