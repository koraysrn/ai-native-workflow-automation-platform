package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanAudit;
import com.awap.file.domain.FileScanAuditEvent;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.domain.FileStorageException;
import com.awap.file.infrastructure.FileRecordRepository;
import com.awap.file.infrastructure.FileScanAuditRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** Scan -> promote orchestration: clean/EICAR, retries, idempotency and failure metrics. */
class FileScanSchedulerTest {

  private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private final FileRecordRepository repository = mock(FileRecordRepository.class);
  private final FileScanAuditRepository auditRepository = mock(FileScanAuditRepository.class);
  private final FileScanEventBus eventBus = mock(FileScanEventBus.class);
  private final InMemoryFileObjectStore objectStore = new InMemoryFileObjectStore();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final FileStorageProperties properties =
      new FileStorageProperties(
          "awap-quarantine",
          "awap-files",
          500L * 1024 * 1024,
          300,
          FileService.defaultAllowedExtensions(),
          3,
          true);

  private ClamAvScanner scanner;
  private FileScanScheduler scheduler;

  @BeforeEach
  void setUp() {
    scanner = mock(ClamAvScanner.class);
    scheduler =
        new FileScanScheduler(
            repository, auditRepository, scanner, objectStore, properties, meterRegistry, eventBus);
  }

  @Test
  void cleanFileIsScannedPromotedAndQuarantineObjectDeleted() {
    FileRecord record = quarantinedRecord("clean.pdf");
    when(scanner.scan(any())).thenReturn(FileScanStatus.CLEAN);

    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.PROMOTED);
    assertThat(record.getPromotedBucket()).isEqualTo("awap-files");
    assertThat(objectStore.copyCalls()).hasSize(1);
    assertThat(objectStore.deleteCalls())
        .containsExactly("awap-quarantine/" + record.getObjectKey());
    assertThat(objectStore.exists("awap-quarantine", record.getObjectKey())).isFalse();
    assertThat(objectStore.exists("awap-files", record.getPromotedObjectKey())).isTrue();
    assertThat(auditEvents()).containsExactly(FileScanAuditEvent.PROMOTED);
    assertThat(
            meterRegistry
                .get(FileScanScheduler.SCAN_DURATION_METRIC)
                .tag("result", "clean")
                .timer()
                .count())
        .isEqualTo(1);
    ArgumentCaptor<FileScanStatusEvent> events = ArgumentCaptor.forClass(FileScanStatusEvent.class);
    verify(eventBus, times(3)).publish(events.capture());
    assertThat(events.getAllValues())
        .extracting(FileScanStatusEvent::status)
        .containsExactly(FileScanStatus.SCANNING, FileScanStatus.CLEAN, FileScanStatus.PROMOTED);
  }

  @Test
  void eicarInfectedFileIsNeverPromotedAndAudited() {
    FileRecord record = quarantinedRecord("eicar.pdf");
    when(scanner.scan(any())).thenReturn(FileScanStatus.INFECTED);

    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.INFECTED);
    assertThat(objectStore.copyCalls()).isEmpty();
    assertThat(
            objectStore.exists(
                "awap-files", FileService.objectKey(TENANT, record.getId(), record.getName())))
        .isFalse();
    assertThat(objectStore.deleteCalls())
        .containsExactly("awap-quarantine/" + record.getObjectKey());
    assertThat(meterRegistry.get(FileScanScheduler.INFECTED_METRIC).counter().count())
        .isEqualTo(1.0);
    assertThat(auditEvents()).containsExactly(FileScanAuditEvent.INFECTED);
  }

  @Test
  void reUploadedInfectedFileIsRejectedAgainAndNeverPromoted() {
    when(scanner.scan(any())).thenReturn(FileScanStatus.INFECTED);
    FileRecord first = quarantinedRecord("eicar.pdf");
    FileRecord second = quarantinedRecord("eicar.pdf");

    scheduler.process(first);
    scheduler.process(second);

    assertThat(first.getStatus()).isEqualTo(FileScanStatus.INFECTED);
    assertThat(second.getStatus()).isEqualTo(FileScanStatus.INFECTED);
    assertThat(objectStore.copyCalls()).isEmpty();
    assertThat(meterRegistry.get(FileScanScheduler.INFECTED_METRIC).counter().count())
        .isEqualTo(2.0);
  }

  @Test
  void infectedFileCanBeRetainedInQuarantineWhenPolicySaysSo() {
    FileStorageProperties retain =
        new FileStorageProperties(
            "awap-quarantine",
            "awap-files",
            500L * 1024 * 1024,
            300,
            FileService.defaultAllowedExtensions(),
            3,
            false);
    scheduler =
        new FileScanScheduler(
            repository, auditRepository, scanner, objectStore, retain, meterRegistry, eventBus);
    FileRecord record = quarantinedRecord("retain.pdf");
    when(scanner.scan(any())).thenReturn(FileScanStatus.INFECTED);

    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.INFECTED);
    assertThat(objectStore.deleteCalls()).isEmpty();
    assertThat(objectStore.exists("awap-quarantine", record.getObjectKey())).isTrue();
  }

  @Test
  void repeatedScansPromoteExactlyOnce() {
    FileRecord record = quarantinedRecord("once.pdf");
    when(scanner.scan(any())).thenReturn(FileScanStatus.CLEAN);

    for (int i = 0; i < 10; i++) {
      scheduler.process(record);
    }

    assertThat(objectStore.copyCalls()).hasSize(1);
    assertThat(objectStore.deleteCalls()).hasSize(1);
    assertThat(record.getStatus()).isEqualTo(FileScanStatus.PROMOTED);
    assertThat(auditEvents().stream().filter(event -> event == FileScanAuditEvent.PROMOTED))
        .hasSize(1);
  }

  @Test
  void copyFailureKeepsSourceAndRetriesOnTheNextTick() {
    FileRecord record = quarantinedRecord("copy-retry.pdf");
    when(scanner.scan(any())).thenReturn(FileScanStatus.CLEAN);
    objectStore.failCopyNext(new FileStorageException("minio down"));

    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.CLEAN);
    assertThat(objectStore.exists("awap-quarantine", record.getObjectKey())).isTrue();
    assertThat(objectStore.deleteCalls()).isEmpty();
    assertThat(meterRegistry.get(FileScanScheduler.PROMOTE_FAIL_METRIC).counter().count())
        .isEqualTo(1.0);
    assertThat(auditEvents()).containsExactly(FileScanAuditEvent.PROMOTE_FAILED);

    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.PROMOTED);
    assertThat(objectStore.exists("awap-quarantine", record.getObjectKey())).isFalse();
    assertThat(objectStore.exists("awap-files", record.getPromotedObjectKey())).isTrue();
    assertThat(objectStore.copyCalls()).hasSize(2);
  }

  @Test
  void deleteFailureAfterControllerLeavesCopyAndRetryIsSafe() {
    FileRecord record = quarantinedRecord("delete-retry.pdf");
    when(scanner.scan(any())).thenReturn(FileScanStatus.CLEAN);
    objectStore.failDeleteNext(new FileStorageException("delete down"));

    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.CLEAN);
    assertThat(objectStore.exists("awap-quarantine", record.getObjectKey())).isTrue();
    String targetKey = FileService.objectKey(TENANT, record.getId(), record.getName());
    assertThat(objectStore.exists("awap-files", targetKey)).isTrue();
    assertThat(meterRegistry.get(FileScanScheduler.PROMOTE_FAIL_METRIC).counter().count())
        .isEqualTo(1.0);

    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.PROMOTED);
    assertThat(objectStore.exists("awap-quarantine", record.getObjectKey())).isFalse();
    assertThat(objectStore.copyCalls()).hasSize(2);
  }

  @Test
  void scanFailureProducesScanFailedAndRetriesSuccessfully() {
    FileRecord record = quarantinedRecord("timeout.pdf");
    when(scanner.scan(any()))
        .thenThrow(
            new AwapException(
                ErrorCode.FILE_SCAN_FAILED, HttpStatus.SERVICE_UNAVAILABLE, "clamd timeout"));

    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.SCAN_FAILED);
    assertThat(record.getLastScanError()).contains("clamd timeout");
    assertThat(auditEvents()).containsExactly(FileScanAuditEvent.SCAN_FAILED);
    assertThat(
            meterRegistry
                .get(FileScanScheduler.SCAN_DURATION_METRIC)
                .tag("result", "failed")
                .timer()
                .count())
        .isEqualTo(1);

    org.mockito.Mockito.doReturn(FileScanStatus.CLEAN).when(scanner).scan(any());
    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.PROMOTED);
    assertThat(record.getScanAttempts()).isEqualTo(2);
  }

  @Test
  void maxAttemptsStopsRetryingSoTheFileCannotStayPendingForever() {
    FileStorageProperties singleAttempt =
        new FileStorageProperties(
            "awap-quarantine",
            "awap-files",
            500L * 1024 * 1024,
            300,
            FileService.defaultAllowedExtensions(),
            1,
            true);
    scheduler =
        new FileScanScheduler(
            repository,
            auditRepository,
            scanner,
            objectStore,
            singleAttempt,
            meterRegistry,
            eventBus);
    FileRecord record = quarantinedRecord("down.pdf");
    when(scanner.scan(any()))
        .thenThrow(
            new AwapException(
                ErrorCode.FILE_SCAN_FAILED, HttpStatus.SERVICE_UNAVAILABLE, "clamd down"));

    scheduler.process(record);
    scheduler.process(record);

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.SCAN_FAILED);
    assertThat(record.getScanAttempts()).isEqualTo(1);
    verify(scanner, times(1)).scan(any());
  }

  @Test
  void scanPendingFilesProcessesAllCandidates() {
    FileRecord one = quarantinedRecord("one.pdf");
    FileRecord two = quarantinedRecord("two.pdf");
    when(repository.findByStatusIn(any())).thenReturn(List.of(one, two));
    when(scanner.scan(any())).thenReturn(FileScanStatus.CLEAN);

    scheduler.scanPendingFiles();

    assertThat(one.getStatus()).isEqualTo(FileScanStatus.PROMOTED);
    assertThat(two.getStatus()).isEqualTo(FileScanStatus.PROMOTED);
    verify(repository).findByStatusIn(any());
  }

  @Test
  void alreadyPromotedRecordDoesNotScanAgain() {
    FileRecord record = quarantinedRecord("done.pdf");
    record.beginScan();
    record.markClean();
    record.markPromoted("awap-files", "awap-files/done.pdf");

    scheduler.process(record);

    verify(scanner, never()).scan(any());
    assertThat(objectStore.copyCalls()).isEmpty();
  }

  private FileRecord quarantinedRecord(String name) {
    FileRecord record =
        new FileRecord(
            TENANT,
            name,
            "application/pdf",
            10,
            "awap-quarantine",
            TENANT + "/" + UUID.randomUUID() + "/" + name);
    objectStore.put("awap-quarantine", record.getObjectKey(), new byte[] {1, 2, 3});
    return record;
  }

  private List<FileScanAuditEvent> auditEvents() {
    ArgumentCaptor<FileScanAudit> captor = ArgumentCaptor.forClass(FileScanAudit.class);
    verify(auditRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
    return captor.getAllValues().stream().map(FileScanAudit::getEvent).toList();
  }
}
