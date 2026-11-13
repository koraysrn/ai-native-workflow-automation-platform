package com.awap.document.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.document.domain.DocumentStatus;
import com.awap.document.domain.DocumentVersion;
import com.awap.document.infrastructure.DocumentVersionRepository;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.file.infrastructure.FileRecordRepository;
import com.awap.usage.application.UsageMeteringService;
import com.awap.usage.domain.UsageEventKeys;
import com.awap.usage.domain.UsageMetric;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document module (ADR-003). Implements versioned document storage: each version's metadata is
 * persisted here while the bytes live in MinIO (whose bucket versioning keeps object history), the
 * version list is queryable, and soft delete + archive preserve the audit trail.
 *
 * <p>The lifecycle publisher writes the outbox record and fans it out in-process. The {@code
 * document.events} consumer re-applies the fact through {@link #applyEvent}: the row already exists
 * in the normal path, so the consumer records a duplicate and never republishes. That is what
 * prevents the previous self-triggered infinite version creation (issue #208).
 */
@Service
public class DocumentService {

  static final String BUCKET = "awap-documents";
  static final String OUTCOME_METRIC = "awap.document.event.outcome";

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  private final DocumentVersionRepository repository;
  private final OutboxEventPublisher outboxEventPublisher;
  private final UsageMeteringService usageMeteringService;
  private final FileRecordRepository fileRecordRepository;
  private final MeterRegistry meterRegistry;

  public DocumentService(
      DocumentVersionRepository repository,
      OutboxEventPublisher outboxEventPublisher,
      UsageMeteringService usageMeteringService,
      FileRecordRepository fileRecordRepository,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.outboxEventPublisher = outboxEventPublisher;
    this.usageMeteringService = usageMeteringService;
    this.fileRecordRepository = fileRecordRepository;
    this.meterRegistry = meterRegistry;
  }

  /** Registers a freshly uploaded file whose antivirus scan has not completed yet. */
  @Transactional
  public DocumentVersion registerPending(UUID tenantId, UUID fileId) {
    DocumentVersion version =
        new DocumentVersion(tenantId, fileId, 1, BUCKET, fileId + "/v1", 0, null);
    repository.save(version);
    publish(tenantId, fileId, "DOCUMENT_REGISTERED", version);
    return version;
  }

  /** Lists every live document version owned by the tenant. */
  @Transactional(readOnly = true)
  public List<DocumentVersion> list(UUID tenantId) {
    return repository.findByTenantIdAndDeletedFalseOrderByCreatedAtDesc(tenantId);
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
    usageMeteringService.record(
        tenantId,
        UsageMetric.DOCUMENT_BYTES,
        Math.max(0L, size),
        UsageEventKeys.documentVersion(version.getId()));
    publish(tenantId, fileId, "DOCUMENT_VERSION_CREATED", version);
    return version;
  }

  /**
   * Applies one {@code document.events} fact idempotently (issue #208).
   *
   * <p>The file must belong to the event's tenant: an event that names a foreign or unknown file is
   * rejected before any row is touched, so RLS and object-level authorization are both preserved. A
   * duplicate create event observes the existing {@code (file_id, version)} row and does nothing;
   * archive/delete re-apply the same terminal state. The method never publishes, so a consumed
   * event cannot trigger another version or another event.
   */
  @Transactional
  public Optional<DocumentVersion> applyEvent(DocumentLifecycleEvent event) {
    requireValid(event);
    if (fileRecordRepository.findByIdAndTenantId(event.fileId(), event.tenantId()).isEmpty()) {
      outcome("rejected", event.eventType());
      log.warn(
          "Rejected document event {} for file {}: the file does not belong to tenant {}.",
          event.eventType(),
          event.fileId(),
          event.tenantId());
      return Optional.empty();
    }
    int versionNumber = event.version() >= 1 ? event.version() : 1;
    Optional<DocumentVersion> existing =
        repository.findByTenantIdAndFileIdAndVersion(
            event.tenantId(), event.fileId(), versionNumber);
    return switch (event.eventType()) {
      case "DOCUMENT_REGISTERED", "DOCUMENT_VERSION_CREATED" ->
          applyCreation(event, versionNumber, existing);
      case "DOCUMENT_ARCHIVED" -> applyArchive(event.eventType(), existing);
      case "DOCUMENT_DELETED" -> applyDelete(event.eventType(), existing);
      default -> {
        outcome("unknown_event", event.eventType());
        yield Optional.empty();
      }
    };
  }

  private Optional<DocumentVersion> applyCreation(
      DocumentLifecycleEvent event, int versionNumber, Optional<DocumentVersion> existing) {
    if (existing.isPresent()) {
      outcome("duplicate", event.eventType());
      return existing;
    }
    DocumentVersion version =
        new DocumentVersion(
            event.tenantId(),
            event.fileId(),
            versionNumber,
            BUCKET,
            objectKey(event, versionNumber),
            Math.max(0L, event.size()),
            event.format());
    if ("DOCUMENT_VERSION_CREATED".equals(event.eventType())) {
      version.markAvailable();
    }
    repository.save(version);
    outcome("created", event.eventType());
    return Optional.of(version);
  }

  private Optional<DocumentVersion> applyArchive(
      String eventType, Optional<DocumentVersion> existing) {
    if (existing.isEmpty()) {
      outcome("missing_version", eventType);
      return Optional.empty();
    }
    existing.get().archive();
    outcome("applied", eventType);
    return existing;
  }

  private Optional<DocumentVersion> applyDelete(
      String eventType, Optional<DocumentVersion> existing) {
    if (existing.isEmpty()) {
      outcome("missing_version", eventType);
      return Optional.empty();
    }
    existing.get().softDelete();
    outcome("applied", eventType);
    return existing;
  }

  private static String objectKey(DocumentLifecycleEvent event, int versionNumber) {
    String objectKey = event.objectKey();
    if (objectKey == null || objectKey.isBlank()) {
      return event.fileId() + "/v" + versionNumber;
    }
    return objectKey;
  }

  private static void requireValid(DocumentLifecycleEvent event) {
    if (event.tenantId() == null || event.fileId() == null) {
      throw new IllegalArgumentException("Document event requires tenantId and fileId.");
    }
    if (event.eventType() == null || event.eventType().isBlank()) {
      throw new IllegalArgumentException("Document event requires an eventType.");
    }
  }

  /** Lists the live (non-soft-deleted) versions of a document, oldest first. */
  @Transactional(readOnly = true)
  public List<DocumentVersion> listVersions(UUID tenantId, UUID fileId) {
    return repository.findByTenantIdAndFileIdAndDeletedFalseOrderByVersionAsc(tenantId, fileId);
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
            "tenantId", tenantId.toString(),
            "eventType", eventType,
            "fileId", fileId.toString(),
            "version", version.getVersion(),
            "size", version.getSize(),
            "format", version.getFormat() == null ? "" : version.getFormat(),
            "objectKey", version.getObjectKey()));
  }

  private void outcome(String outcome, String eventType) {
    Counter.builder(OUTCOME_METRIC)
        .tag("outcome", outcome)
        .tag("eventType", eventType == null ? "UNKNOWN" : eventType)
        .register(meterRegistry)
        .increment();
  }
}
