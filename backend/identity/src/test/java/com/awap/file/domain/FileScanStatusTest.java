package com.awap.file.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Guards the quarantine -> scanning -> clean -> promoted lifecycle and retry edges. */
class FileScanStatusTest {

  private FileRecord quarantined() {
    return new FileRecord(
        UUID.randomUUID(),
        "doc.pdf",
        "application/pdf",
        10,
        "awap-quarantine",
        "tenant/file/doc.pdf");
  }

  @Test
  void quarantinedCanOnlyStartScanning() {
    assertThat(FileScanStatus.QUARANTINED.canTransitionTo(FileScanStatus.SCANNING)).isTrue();
    assertThat(FileScanStatus.QUARANTINED.canTransitionTo(FileScanStatus.CLEAN)).isFalse();
    assertThat(FileScanStatus.QUARANTINED.canTransitionTo(FileScanStatus.PROMOTED)).isFalse();
  }

  @Test
  void scanningCanEndCleanInfectedOrFailed() {
    assertThat(FileScanStatus.SCANNING.canTransitionTo(FileScanStatus.CLEAN)).isTrue();
    assertThat(FileScanStatus.SCANNING.canTransitionTo(FileScanStatus.INFECTED)).isTrue();
    assertThat(FileScanStatus.SCANNING.canTransitionTo(FileScanStatus.SCAN_FAILED)).isTrue();
    assertThat(FileScanStatus.SCANNING.canTransitionTo(FileScanStatus.PROMOTED)).isFalse();
  }

  @Test
  void onlyCleanCanBePromoted() {
    assertThat(FileScanStatus.CLEAN.canTransitionTo(FileScanStatus.PROMOTED)).isTrue();
    assertThat(FileScanStatus.PROMOTED.canTransitionTo(FileScanStatus.CLEAN)).isFalse();
    assertThat(FileScanStatus.INFECTED.canTransitionTo(FileScanStatus.PROMOTED)).isFalse();
    assertThat(FileScanStatus.PROMOTED.isTerminal()).isTrue();
    assertThat(FileScanStatus.INFECTED.isTerminal()).isTrue();
  }

  @Test
  void failedScanCanBeRetried() {
    assertThat(FileScanStatus.SCAN_FAILED.canTransitionTo(FileScanStatus.SCANNING)).isTrue();
  }

  @Test
  void illegalTransitionIsRejectedByTheDomain() {
    assertThatThrownBy(() -> FileScanStatus.CLEAN.transitionTo(FileScanStatus.INFECTED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Illegal")
        .hasMessageContaining("CLEAN");
  }

  @Test
  void recordWalksTheHappyPathAndKeepsPromotionMetadata() {
    FileRecord record = quarantined();
    record.beginScan();
    assertThat(record.getStatus()).isEqualTo(FileScanStatus.SCANNING);
    assertThat(record.getScanAttempts()).isEqualTo(1);

    record.markClean();
    record.markPromoted("awap-files", "tenant/" + record.getId() + "/doc.pdf");

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.PROMOTED);
    assertThat(record.getPromotedBucket()).isEqualTo("awap-files");
    assertThat(record.getPromotedObjectKey()).contains(record.getId().toString(), "doc.pdf");
    assertThat(record.getPromotedAt()).isNotNull();
    assertThat(record.getLastScanError()).isNull();
  }

  @Test
  void scanFailureIsRecordedAndRetryIsAllowedUntilTheLimit() {
    FileRecord record = quarantined();
    record.beginScan();
    record.markScanFailed("clamd timeout");
    assertThat(record.getStatus()).isEqualTo(FileScanStatus.SCAN_FAILED);
    assertThat(record.getLastScanError()).isEqualTo("clamd timeout");
    assertThat(record.canRetryScan(5)).isTrue();

    record.beginScan();
    assertThat(record.getScanAttempts()).isEqualTo(2);
    assertThat(record.canRetryScan(2)).isFalse();
  }

  @Test
  void infectedIsTerminalAndCannotBePromoted() {
    FileRecord record = quarantined();
    record.beginScan();
    record.markInfected();

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.INFECTED);
    assertThatThrownBy(() -> record.markPromoted("awap-files", "key"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void longErrorMessagesAreTruncated() {
    FileRecord record = quarantined();
    record.beginScan();
    record.markScanFailed("x".repeat(900));

    assertThat(record.getLastScanError()).hasSize(500);
  }
}
