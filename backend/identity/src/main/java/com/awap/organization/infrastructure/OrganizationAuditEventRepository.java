package com.awap.organization.infrastructure;

import com.awap.organization.domain.OrganizationAuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for the immutable organization audit trail. */
public interface OrganizationAuditEventRepository
    extends JpaRepository<OrganizationAuditEvent, UUID> {

  List<OrganizationAuditEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
