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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentServiceTest {

  private final DocumentVersionRepository repository = mock(DocumentVersionRepository.class);
  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final DocumentService service = new DocumentService(repository, outbox);

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

    DocumentVersion version =
        service.createVersion(UUID.randomUUID(), file, 10L, "pdf", "k2");

    assertThat(version.getVersion()).isEqualTo(6);
  }

  @Test
  void softDeleteHidesVersionFromListings() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    DocumentVersion version = new DocumentVersion(tenant, file, 1, "b", "k", 5, "pdf");
    when(repository.findByIdAndTenantId(version.getId(), tenant)).thenReturn(Optional.of(version));
    when(repository.findByFileIdAndDeletedFalseOrderByVersionAsc(file)).thenReturn(List.of());

    service.softDelete(tenant, version.getId());

    assertThat(version.isDeleted()).isTrue();
    assertThat(service.listVersions(tenant, file)).isEmpty();
  }

  @Test
  void listVersionsFiltersOtherTenants() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    DocumentVersion a = new DocumentVersion(tenantA, file, 1, "b", "k", 5, "pdf");
    DocumentVersion b = new DocumentVersion(tenantB, file, 2, "b", "k", 5, "pdf");
    when(repository.findByFileIdAndDeletedFalseOrderByVersionAsc(file)).thenReturn(List.of(a, b));

    assertThat(service.listVersions(tenantA, file)).containsExactly(a);
  }
}
