package com.awap.engine.infrastructure;

import com.awap.engine.domain.ExecutionLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for execution logs. */
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {

  List<ExecutionLog> findByWorkflowInstanceIdOrderByCreatedAtAsc(UUID workflowInstanceId);

  /**
   * Tenant-scoped, orderable page of the execution log used by the replay engine. The caller passes
   * a {@link Pageable} sorted by {@code createdAt} and {@code id} so the fold is deterministic even
   * when several rows share a timestamp.
   */
  @Query(
      "select log from ExecutionLog log"
          + " where log.tenantId = :tenantId and log.workflowInstanceId = :instanceId")
  Page<ExecutionLog> findReplayPage(
      @Param("tenantId") UUID tenantId, @Param("instanceId") UUID instanceId, Pageable pageable);
}
