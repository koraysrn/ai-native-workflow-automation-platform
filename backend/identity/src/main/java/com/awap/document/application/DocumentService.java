package com.awap.document.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.document.domain.DocumentStatus;
import com.awap.document.domain.DocumentVersion;
import com.awap.document.infrastructure.DocumentVersionRepository;
import com.awap.engine.application.OutboxEventPublisher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document module (ADR-003). Implements versioned document storage: each version's metadata is
 * persisted here while the bytes live in MinIO (whose bucket versioning keeps object history), the
 * version list is queryable, and soft delete + archive preserve the audit trail.
 */
@Service
public class DocumentService {

  static final String BUCKET = "awap-documents";

  private final DocumentVersionRepository repository;
  private final OutboxEventPublisher outboxEventPublisher;

  public DocumentService(
      DocumentVersionRepository repository, OutboxEventPublisher outboxEventPublisher) {
    this.repository = repository;
    this.outboxEventPublisher = outboxEventPublisher;
  }

  /** Registers a brand-new document (version 1) or the first observed event for a file. */
  @Transactional
  public DocumentVersion register(UUID tenantId, UUID fileId, DocumentStatus status) {
    DocumentVersion version =
        new DocumentVersion(tenantId, fileId, 1, BUCKET, fileId + "/v1", 0, null);
    if (status == DocumentStatus.AVAILABLE) {
      version.markAvailable();
    } else if (status == DocumentStatus.ARCHIVED) {
      version.archive();
    }
    repository.save(version);
    publish(tenantId, fileId, "DOCUMENT_REGISTERED", version);
    return version;
  }

  /** Creates the next version of a document. */
  @Transactional
  public DocumentVersion createVersion(
      UUID tenantId, UUID fileId, long size, String format, String objectKey) {
    int next =
        repository.findTopByFileIdOrderByVersionDesc(fileId).map(v -> v.getVersion() + 1).orElse(1);
    DocumentVersion version =
        new DocumentVersion(tenantId, fileId, next, BUCKET, objectKey, size, format);
    version.markAvailable();
    repository.save(version);
    publish(tenantId, fileId, "DOCUMENT_VERSION_CREATED", version);
    return version;
  }

  /** Lists the live (non-soft-deleted) versions of a document, oldest first. */
  @Transactional(readOnly = true)
  public List<DocumentVersion> listVersions(UUID tenantId, UUID fileId) {
    return repository.findByFileIdAndDeletedFalseOrderByVersionAsc(fileId).stream()
        .filter(v -> v.getTenantId().equals(tenantId))
        .toList();
  }

  /** Archives a document version without deleting it (retention-compatible). */
  @Transactional
  public DocumentVersion archive(UUID tenantId, UUID versionId) {
    DocumentVersion version = find(tenantId, versionId);
    version.archive();
    publish(tenantId, version.getFileId(), "DOCUMENT_ARCHIVED", version);
    return version;
  }

  /** Soft-deletes a document version: hidden from listings, kept for audit/retention. */
  @Transactional
  public DocumentVersion softDelete(UUID tenantId, UUID versionId) {
    DocumentVersion version = find(tenantId, versionId);
    version.softDelete();
    publish(tenantId, version.getFileId(), "DOCUMENT_DELETED", version);
    return version;
  }

  private DocumentVersion find(UUID tenantId, UUID versionId) {
    return repository
        .findByIdAndTenantId(versionId, tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "Document version not found."));
  }

  private void publish(UUID tenantId, UUID fileId, String eventType, DocumentVersion version) {
    outboxEventPublisher.publish(
        tenantId,
        "DOCUMENT",
        fileId,
        eventType,
        Map.of(
            "fileId", fileId.toString(),
            "version", version.getVersion(),
            "size", version.getSize(),
            "format", version.getFormat() == null ? "" : version.getFormat()));
  }
}
