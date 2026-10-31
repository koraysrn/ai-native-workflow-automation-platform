package com.awap.file.infrastructure;

import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for file records. */
public interface FileRecordRepository extends JpaRepository<FileRecord, UUID> {

  Optional<FileRecord> findByIdAndTenantId(UUID id, UUID tenantId);

  List<FileRecord> findByStatusIn(Collection<FileScanStatus> statuses);

  List<FileRecord> findByIdInAndTenantId(Collection<UUID> ids, UUID tenantId);

  List<FileRecord> findByTenantId(UUID tenantId);
}
