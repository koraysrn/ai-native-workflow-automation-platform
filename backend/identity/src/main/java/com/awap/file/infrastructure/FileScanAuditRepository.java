package com.awap.file.infrastructure;

import com.awap.file.domain.FileScanAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Immutable audit trail for file scan and promotion outcomes. */
public interface FileScanAuditRepository extends JpaRepository<FileScanAudit, UUID> {

  List<FileScanAudit> findByTenantIdAndFileIdOrderByCreatedAtAsc(UUID tenantId, UUID fileId);
}
