package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.infrastructure.FileRecordRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Issue #200: the submission-time file guard rejects foreign, pending, infected, oversized and
 * unsupported files and returns metadata only for a promoted, tenant-owned file.
 */
class FileServiceSubmissionTest {

  private static final long MAX_SIZE = 500L * 1024 * 1024;

  private final UUID tenantId = UUID.randomUUID();
  private FileRecordRepository repository;
  private FileService service;

  @BeforeEach
  void setUp() {
    repository = mock(FileRecordRepository.class);
    FileStorageProperties properties =
        new FileStorageProperties(
            "quarantine", "promoted", MAX_SIZE, 60, Set.of("pdf", "png"), 5, true, false);
    service =
        new FileService(
            repository, mock(FileObjectStore.class), properties, mock(FileScanEventBus.class));
  }

  private FileRecord record(String name, long size) {
    return new FileRecord(UUID.randomUUID(), tenantId, name, "application/pdf", size, "q", "key");
  }

  private void stored(FileRecord record) {
    when(repository.findByIdAndTenantId(record.getId(), tenantId)).thenReturn(Optional.of(record));
  }

  private static void promote(FileRecord record) {
    record.beginScan();
    record.markClean();
    record.markPromoted("promoted", "key");
  }

  @Test
  void promotedTenantOwnedFileReturnsMetadata() {
    FileRecord record = record("invoice.pdf", 1024);
    promote(record);
    stored(record);

    FileService.SubmissionFile file = service.submissionFile(tenantId, record.getId());

    assertThat(file.fileId()).isEqualTo(record.getId());
    assertThat(file.name()).isEqualTo("invoice.pdf");
    assertThat(file.size()).isEqualTo(1024);
    assertThat(file.contentType()).isEqualTo("application/pdf");
    assertThat(file.status()).isEqualTo(FileScanStatus.PROMOTED);
  }

  @Test
  void missingOrForeignFileIsNotFound() {
    UUID fileId = UUID.randomUUID();
    when(repository.findByIdAndTenantId(fileId, tenantId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.submissionFile(tenantId, fileId))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void quarantinedFileIsConflict() {
    FileRecord record = record("invoice.pdf", 1024);
    stored(record);

    assertThatThrownBy(() -> service.submissionFile(tenantId, record.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void scanFailedFileIsConflict() {
    FileRecord record = record("invoice.pdf", 1024);
    record.beginScan();
    record.markScanFailed("clamd unreachable");
    stored(record);

    assertThatThrownBy(() -> service.submissionFile(tenantId, record.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void infectedFileIsForbidden() {
    FileRecord record = record("invoice.pdf", 1024);
    record.beginScan();
    record.markInfected();
    stored(record);

    assertThatThrownBy(() -> service.submissionFile(tenantId, record.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void unsupportedFormatIsRejectedAtReadTime() {
    FileRecord record = record("payload.exe", 1024);
    promote(record);
    stored(record);

    assertThatThrownBy(() -> service.submissionFile(tenantId, record.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
  }

  @Test
  void oversizedFileIsRejectedAtReadTime() {
    FileRecord record = record("invoice.pdf", MAX_SIZE + 1);
    promote(record);
    stored(record);

    assertThatThrownBy(() -> service.submissionFile(tenantId, record.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
  }

  @Test
  void emptyFileIsRejectedAtReadTime() {
    FileRecord record = record("invoice.pdf", 0);
    promote(record);
    stored(record);

    assertThatThrownBy(() -> service.submissionFile(tenantId, record.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
