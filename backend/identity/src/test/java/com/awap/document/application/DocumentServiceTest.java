package com.awap.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.document.domain.DocumentStatus;
import com.awap.document.domain.DocumentVersion;
import com.awap.document.infrastructure.DocumentVersionRepository;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.usage.application.InMemoryUsageCounterStore;
import com.awap.usage.application.UsageMeteringService;
import com.awap.usage.domain.UsageMetric;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentServiceTest {

  private final DocumentVersionRepository repository = mock(DocumentVersionRepository.class);
  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final UsageMeteringService usageMeteringService = mock(UsageMeteringService.class);
  private final DocumentService service =
      new DocumentService(repository, outbox, usageMeteringService);

  @Test
  void createVersionStartsAtOneWhenNoPreviousVersion() {
    when(repository.findTopByFileIdOrderByVersionDesc(any())).thenReturn(Optional.empty());
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();

    DocumentVersion version = service.createVersion(tenant, file, 10L, "pdf", "k");

    assertThat(version.getVersion()).isEqualTo(1);
    assertThat(version.getStatus()).isEqualTo(DocumentStatus.AVAILABLE);
    verify(outbox).publish(any(), any(), any(), any(), any());
  }

  @Test
  void createVersionIncrementsFromPrevious() {
    UUID file = UUID.randomUUID();
    DocumentVersion previous = new DocumentVersion(UUID.randomUUID(), file, 5, "b", "k", 1, "txt");
    when(repository.findTopByFileIdOrderByVersionDesc(file)).thenReturn(Optional.of(previous));

    DocumentVersion version = service.createVersion(UUID.randomUUID(), file, 10L, "pdf", "k2");

    assertThat(version.getVersion()).isEqualTo(6);
  }

  @Test
  void softDeleteHidesVersionFromListings() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    DocumentVersion version = new DocumentVersion(tenant, file, 1, "b", "k", 5, "pdf");
    when(repository.findByIdAndTenantId(version.getId(), tenant)).thenReturn(Optional.of(version));
    when(repository.findByTenantIdAndFileIdAndDeletedFalseOrderByVersionAsc(tenant, file))
        .thenReturn(List.of());

    service.softDelete(tenant, version.getId());

    assertThat(version.isDeleted()).isTrue();
    assertThat(service.listVersions(tenant, file)).isEmpty();
  }

  @Test
  void listVersionsIsTenantScopedInTheQuery() {
    UUID tenantA = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    DocumentVersion a = new DocumentVersion(tenantA, file, 1, "b", "k", 5, "pdf");
    when(repository.findByTenantIdAndFileIdAndDeletedFalseOrderByVersionAsc(tenantA, file))
        .thenReturn(List.of(a));

    assertThat(service.listVersions(tenantA, file)).containsExactly(a);
  }

  @Test
  void registerPendingCreatesVersionOnePending() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();

    DocumentVersion version = service.registerPending(tenant, file);

    assertThat(version.getVersion()).isEqualTo(1);
    assertThat(version.getStatus()).isEqualTo(DocumentStatus.PENDING);
    verify(repository).save(version);
  }

  @Test
  void createVersionAddsTheExactByteCountToTheDocumentBytesCounter() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    when(repository.findTopByFileIdOrderByVersionDesc(file)).thenReturn(Optional.empty());

    InMemoryUsageCounterStore store = new InMemoryUsageCounterStore();
    UsageMeteringService metering =
        new UsageMeteringService(store, new SimpleMeterRegistry(), false, Clock.systemUTC());
    DocumentService meteredService = new DocumentService(repository, outbox, metering);

    meteredService.createVersion(tenant, file, 2048L, "pdf", "k");

    assertThat(store.value(tenant, LocalDate.now(ZoneOffset.UTC), UsageMetric.DOCUMENT_BYTES))
        .isEqualTo(2048L);
  }
}
