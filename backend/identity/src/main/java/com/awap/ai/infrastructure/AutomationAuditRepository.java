package com.awap.ai.infrastructure;

import com.awap.ai.domain.AutomationAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only access to automation audit records. */
public interface AutomationAuditRepository extends JpaRepository<AutomationAudit, UUID> {

  List<AutomationAudit> findByTenantIdAndInstanceIdOrderByCreatedAtAsc(
      UUID tenantId, UUID instanceId);
}
