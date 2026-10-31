package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileScanStatusEventTest {

  @Test
  void rejectsMissingTenantFileAndStatus() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    assertThatThrownBy(
            () -> new FileScanStatusEvent(null, file, "a.pdf", FileScanStatus.CLEAN, "", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId");
    assertThatThrownBy(
            () -> new FileScanStatusEvent(tenant, null, "a.pdf", FileScanStatus.CLEAN, "", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fileId");
    assertThatThrownBy(() -> new FileScanStatusEvent(tenant, file, "a.pdf", null, "", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status");
  }

  @Test
  void normalizesNullableDetailAndTimestamp() {
    FileScanStatusEvent event =
        new FileScanStatusEvent(
            UUID.randomUUID(), UUID.randomUUID(), "a.pdf", FileScanStatus.SCANNING, null, null);

    assertThat(event.detail()).isEmpty();
    assertThat(event.occurredAt()).isNotNull();
  }

  @Test
  void factoriesProjectTheCurrentAndExplicitStatus() {
    FileRecord record =
        new FileRecord(
            UUID.randomUUID(),
            "a.pdf",
            "application/pdf",
            10,
            "awap-quarantine",
            "tenant/file/a.pdf");

    FileScanStatusEvent current = FileScanStatusEvent.of(record, "uploaded");
    assertThat(current.tenantId()).isEqualTo(record.getTenantId());
    assertThat(current.fileId()).isEqualTo(record.getId());
    assertThat(current.name()).isEqualTo("a.pdf");
    assertThat(current.status()).isEqualTo(FileScanStatus.QUARANTINED);
    assertThat(current.detail()).isEqualTo("uploaded");

    FileScanStatusEvent explicit = FileScanStatusEvent.of(record, FileScanStatus.CLEAN, "ok");
    assertThat(explicit.status()).isEqualTo(FileScanStatus.CLEAN);
    assertThat(explicit.detail()).isEqualTo("ok");
  }
}
