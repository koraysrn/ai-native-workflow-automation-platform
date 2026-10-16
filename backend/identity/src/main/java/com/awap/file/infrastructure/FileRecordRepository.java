package com.awap.file.infrastructure;

import com.awap.file.domain.FileRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for file records. */
public interface FileRecordRepository extends JpaRepository<FileRecord, UUID> {

  Optional<FileRecord> findByIdAndTenantId(UUID id, UUID tenantId);
}
