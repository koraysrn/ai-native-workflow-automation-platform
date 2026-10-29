package com.awap.engine.infrastructure;

import com.awap.engine.domain.ReplayRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for the immutable replay audit ledger. */
public interface ReplayRunRepository extends JpaRepository<ReplayRun, UUID> {

  List<ReplayRun> findByTenantIdAndWorkflowInstanceIdOrderByCreatedAtDesc(
      UUID tenantId, UUID workflowInstanceId);
}
