package com.awap.document.infrastructure;

import com.awap.document.domain.DocumentVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for document version metadata. */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

  Optional<DocumentVersion> findTopByFileIdOrderByVersionDesc(UUID fileId);

  List<DocumentVersion> findByFileIdAndDeletedFalseOrderByVersionAsc(UUID fileId);

  List<DocumentVersion> findByTenantIdAndFileIdAndDeletedFalseOrderByVersionAsc(
      UUID tenantId, UUID fileId);

  List<DocumentVersion> findByTenantIdAndDeletedFalseOrderByCreatedAtDesc(UUID tenantId);

  Optional<DocumentVersion> findByIdAndTenantId(UUID id, UUID tenantId);
}
