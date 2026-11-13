package com.awap.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.document.domain.DocumentStatus;
import com.awap.document.domain.DocumentVersion;
import com.awap.document.infrastructure.DocumentVersionRepository;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.file.domain.FileRecord;
import com.awap.file.infrastructure.FileRecordRepository;
import com.awap.usage.application.UsageMeteringService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Idempotent application of {@code document.events} facts (issue #208). */
class DocumentEventApplicationTest {

  private final DocumentVersionRepository repository = mock(DocumentVersionRepository.class);
  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final UsageMeteringService metering = mock(UsageMeteringService.class);
  private final FileRecordRepository fileRecords = mock(FileRecordRepository.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final UUID tenantId = UUID.randomUUID();
  private final UUID fileId = UUID.randomUUID();

  private DocumentService service;

  @BeforeEach
  void setUp() {
    service = new DocumentService(repository, outbox, metering, fileRecords, registry);
  }

  private void fileBelongsToTenant() {
    FileRecord record = new FileRecord(fileId, tenantId, "a.pdf", "application/pdf", 10, "q", "k");
    when(fileRecords.findByIdAndTenantId(fileId, tenantId)).thenReturn(Optional.of(record));
  }

  private double outcome(String outcome, String eventType) {
    var counter =
        registry
            .find(DocumentService.OUTCOME_METRIC)
            .tag("outcome", outcome)
            .tag("eventType", eventType)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void aRegistrationCreatesThePendingVersionOnce() {
    fileBelongsToTenant();
    when(repository.findByTenantIdAndFileIdAndVersion(tenantId, fileId, 1))
        .thenReturn(Optional.empty());

    Optional<DocumentVersion> applied =
        service.applyEvent(
            new DocumentLifecycleEvent(
                tenantId, fileId, "DOCUMENT_REGISTERED", 1, 10L, "pdf", fileId + "/v1"));

    assertThat(applied).isPresent();
    assertThat(applied.get().getStatus()).isEqualTo(DocumentStatus.PENDING);
    assertThat(applied.get().getVersion()).isEqualTo(1);
    verify(repository).save(applied.get());
    assertThat(outcome("created", "DOCUMENT_REGISTERED")).isEqualTo(1.0);
    verify(outbox, never()).publish(any(), any(), any(), any(), any());
  }

  @Test
  void aVersionCreationCreatesTheAvailableVersionOnce() {
    fileBelongsToTenant();
    when(repository.findByTenantIdAndFileIdAndVersion(tenantId, fileId, 2))
        .thenReturn(Optional.empty());

    Optional<DocumentVersion> applied =
        service.applyEvent(
            new DocumentLifecycleEvent(
                tenantId, fileId, "DOCUMENT_VERSION_CREATED", 2, 20L, "pdf", fileId + "/v2"));

    assertThat(applied).isPresent();
    assertThat(applied.get().getStatus()).isEqualTo(DocumentStatus.AVAILABLE);
    assertThat(applied.get().getObjectKey()).isEqualTo(fileId + "/v2");
    assertThat(outcome("created", "DOCUMENT_VERSION_CREATED")).isEqualTo(1.0);
  }

  @Test
  void aBlankObjectKeyFallsBackToTheDeterministicKey() {
    fileBelongsToTenant();
    when(repository.findByTenantIdAndFileIdAndVersion(tenantId, fileId, 3))
        .thenReturn(Optional.empty());

    Optional<DocumentVersion> applied =
        service.applyEvent(
            new DocumentLifecycleEvent(
                tenantId, fileId, "DOCUMENT_VERSION_CREATED", 3, 20L, "pdf", " "));

    assertThat(applied).isPresent();
    assertThat(applied.get().getObjectKey()).isEqualTo(fileId + "/v3");
  }

  @Test
  void aDuplicateCreateEventDoesNotCreateAnotherVersion() {
    fileBelongsToTenant();
    DocumentVersion existing =
        new DocumentVersion(tenantId, fileId, 1, DocumentService.BUCKET, "k", 10, "pdf");
    when(repository.findByTenantIdAndFileIdAndVersion(tenantId, fileId, 1))
        .thenReturn(Optional.of(existing));

    Optional<DocumentVersion> applied =
        service.applyEvent(
            new DocumentLifecycleEvent(
                tenantId, fileId, "DOCUMENT_VERSION_CREATED", 1, 10L, "pdf", "k"));

    assertThat(applied).contains(existing);
    verify(repository, never()).save(any());
    assertThat(outcome("duplicate", "DOCUMENT_VERSION_CREATED")).isEqualTo(1.0);
  }

  @Test
  void archiveAndDeleteAreAppliedIdempotently() {
    fileBelongsToTenant();
    DocumentVersion existing =
        new DocumentVersion(tenantId, fileId, 1, DocumentService.BUCKET, "k", 10, "pdf");
    when(repository.findByTenantIdAndFileIdAndVersion(tenantId, fileId, 1))
        .thenReturn(Optional.of(existing));

    service.applyEvent(
        new DocumentLifecycleEvent(tenantId, fileId, "DOCUMENT_ARCHIVED", 1, 10L, "pdf", "k"));
    assertThat(existing.getStatus()).isEqualTo(DocumentStatus.ARCHIVED);

    service.applyEvent(
        new DocumentLifecycleEvent(tenantId, fileId, "DOCUMENT_DELETED", 1, 10L, "pdf", "k"));
    assertThat(existing.isDeleted()).isTrue();
    assertThat(existing.getStatus()).isEqualTo(DocumentStatus.ARCHIVED);
    assertThat(outcome("applied", "DOCUMENT_DELETED")).isEqualTo(1.0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DOCUMENT_ARCHIVED", "DOCUMENT_DELETED"})
  void aMissingVersionReferenceIsHandledWithoutACrash(String eventType) {
    fileBelongsToTenant();
    when(repository.findByTenantIdAndFileIdAndVersion(tenantId, fileId, 4))
        .thenReturn(Optional.empty());

    Optional<DocumentVersion> applied =
        service.applyEvent(
            new DocumentLifecycleEvent(tenantId, fileId, eventType, 4, 10L, "pdf", "k"));

    assertThat(applied).isEmpty();
    assertThat(outcome("missing_version", eventType)).isEqualTo(1.0);
  }

  @Test
  void aCrossTenantEventIsRejectedBeforeAnyWrite() {
    UUID foreignTenant = UUID.randomUUID();
    when(fileRecords.findByIdAndTenantId(fileId, foreignTenant)).thenReturn(Optional.empty());

    Optional<DocumentVersion> applied =
        service.applyEvent(
            new DocumentLifecycleEvent(
                foreignTenant, fileId, "DOCUMENT_VERSION_CREATED", 1, 10L, "pdf", "k"));

    assertThat(applied).isEmpty();
    verify(repository, never()).findByTenantIdAndFileIdAndVersion(any(), any(), anyInt());
    verify(repository, never()).save(any());
    assertThat(outcome("rejected", "DOCUMENT_VERSION_CREATED")).isEqualTo(1.0);
  }

  @Test
  void anUnknownEventTypeIsIgnored() {
    fileBelongsToTenant();
    when(repository.findByTenantIdAndFileIdAndVersion(tenantId, fileId, 1))
        .thenReturn(Optional.empty());

    Optional<DocumentVersion> applied =
        service.applyEvent(
            new DocumentLifecycleEvent(tenantId, fileId, "DOCUMENT_RENAMED", 1, 10L, "pdf", "k"));

    assertThat(applied).isEmpty();
    verify(repository, never()).save(any());
    assertThat(outcome("unknown_event", "DOCUMENT_RENAMED")).isEqualTo(1.0);
  }

  @Test
  void anEventWithoutTenantFileOrTypeIsRejected() {
    assertThatThrownBy(
            () ->
                service.applyEvent(
                    new DocumentLifecycleEvent(
                        null, fileId, "DOCUMENT_REGISTERED", 1, 1L, "pdf", "k")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId and fileId");
    assertThatThrownBy(
            () ->
                service.applyEvent(
                    new DocumentLifecycleEvent(
                        tenantId, null, "DOCUMENT_REGISTERED", 1, 1L, "pdf", "k")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId and fileId");
    assertThatThrownBy(
            () ->
                service.applyEvent(
                    new DocumentLifecycleEvent(tenantId, fileId, " ", 1, 1L, "pdf", "k")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventType");
  }

  @Test
  void aZeroVersionIsTreatedAsVersionOne() {
    fileBelongsToTenant();
    when(repository.findByTenantIdAndFileIdAndVersion(tenantId, fileId, 1))
        .thenReturn(Optional.empty());

    Optional<DocumentVersion> applied =
        service.applyEvent(
            new DocumentLifecycleEvent(
                tenantId, fileId, "DOCUMENT_REGISTERED", 0, 10L, "pdf", "k"));

    assertThat(applied).isPresent();
    assertThat(applied.get().getVersion()).isEqualTo(1);
  }
}
