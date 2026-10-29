package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.domain.FileStorageException;
import com.awap.file.infrastructure.FileRecordRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class FileServiceTest {

  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private final FileRecordRepository repository = mock(FileRecordRepository.class);
  private final InMemoryFileObjectStore objectStore = new InMemoryFileObjectStore();
  private final FileStorageProperties properties =
      new FileStorageProperties(
          "awap-quarantine",
          "awap-files",
          500L * 1024 * 1024,
          900,
          FileService.defaultAllowedExtensions(),
          5,
          true);
  private FileService service;

  @BeforeEach
  void setUp() {
    service = new FileService(repository, objectStore, properties);
  }

  @Test
  void createUploadTargetsQuarantineOnlyAndPresignsAtMostFiveMinutes() {
    FileService.UploadSlot slot = service.createUpload(TENANT, "doc.pdf", "application/pdf", 10);

    ArgumentCaptor<FileRecord> saved = ArgumentCaptor.forClass(FileRecord.class);
    verify(repository).save(saved.capture());
    FileRecord record = saved.getValue();

    assertThat(record.getStatus()).isEqualTo(FileScanStatus.QUARANTINED);
    assertThat(record.getBucket()).isEqualTo("awap-quarantine");
    assertThat(record.getObjectKey()).startsWith(TENANT.toString());
    assertThat(slot.uploadUrl()).contains("awap-quarantine", "method=PUT");
    assertThat(objectStore.presignTtls()).containsExactly(Duration.ofMinutes(5));
    assertThat(properties.presignTtlSeconds()).isEqualTo(300);
    assertThat(objectStore.ensuredBuckets())
        .contains("awap-quarantine:true:1", "awap-files:false:365");
  }

  @Test
  void everyAllowedExtensionIsAccepted() {
    List<String> extensions = List.of("pdf", "docx", "xlsx", "pptx", "png", "jpg", "txt");
    for (String extension : extensions) {
      FileService.UploadSlot slot =
          service.createUpload(TENANT, "file." + extension, "application/octet-stream", 10);
      assertThat(slot.fileId()).isNotNull();
    }
    assertThat(objectStore.presignTtls()).hasSize(extensions.size());
  }

  @Test
  void emptyFileIsRejectedWithBadRequest() {
    assertThatThrownBy(() -> service.createUpload(TENANT, "empty.pdf", "application/pdf", 0))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_EMPTY);
              assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
  }

  @Test
  void oversizedFileIsRejectedWith413BeforePresigning() {
    assertThatThrownBy(
            () ->
                service.createUpload(TENANT, "big.pdf", "application/pdf", 500L * 1024 * 1024 + 1))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE);
              assertThat(ex.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            });
    assertThat(objectStore.presignTtls()).isEmpty();
  }

  @Test
  void unsupportedExtensionIsRejectedWith415() {
    assertThatThrownBy(
            () -> service.createUpload(TENANT, "malware.exe", "application/x-msdownload", 10))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_FORMAT_UNSUPPORTED);
              assertThat(ex.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            });
  }

  @Test
  void pathTraversalNamesAreSanitizedToASingleSegment() {
    service.createUpload(TENANT, "../../etc/passwd.pdf", "application/pdf", 10);
    service.createUpload(TENANT, "..\\..\\windows\\evil.pdf", "application/pdf", 10);

    ArgumentCaptor<FileRecord> saved = ArgumentCaptor.forClass(FileRecord.class);
    verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
    for (FileRecord record : saved.getAllValues()) {
      assertThat(record.getObjectKey()).doesNotContain("..", "\\");
      assertThat(record.getObjectKey()).startsWith(TENANT + "/" + record.getId() + "/");
    }
    assertThat(saved.getAllValues())
        .extracting(FileRecord::getObjectKey)
        .anySatisfy(key -> assertThat(key).endsWith("/passwd.pdf"))
        .anySatisfy(key -> assertThat(key).endsWith("/evil.pdf"));
  }

  @Test
  void minioFailureIsReportedAsServerError() {
    objectStore.failEnsure(new FileStorageException("down"));

    assertThatThrownBy(() -> service.createUpload(TENANT, "a.pdf", "application/pdf", 1))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              assertThat(ex).hasMessageContaining("MinIO");
            });
  }

  @Test
  void promotedFileGetsPresignedGetOnPromotedBucket() {
    FileRecord record = promotedRecord();
    when(repository.findByIdAndTenantId(record.getId(), TENANT)).thenReturn(Optional.of(record));

    String url = service.downloadUrl(TENANT, record.getId());

    assertThat(url).contains("awap-files", "method=GET");
    assertThat(objectStore.presignTtls()).containsExactly(Duration.ofMinutes(5));
  }

  @Test
  void infectedFileDownloadIsForbidden() {
    FileRecord record = recordWith(FileScanStatus.INFECTED);
    when(repository.findByIdAndTenantId(record.getId(), TENANT)).thenReturn(Optional.of(record));

    assertThatThrownBy(() -> service.downloadUrl(TENANT, record.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_SCAN_REJECTED);
              assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
            });
  }

  @Test
  void scanFailedDownloadIsUnavailable() {
    FileRecord record = recordWith(FileScanStatus.SCAN_FAILED);
    when(repository.findByIdAndTenantId(record.getId(), TENANT)).thenReturn(Optional.of(record));

    assertThatThrownBy(() -> service.downloadUrl(TENANT, record.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_SCAN_FAILED);
              assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
  }

  @Test
  void stillScanningDownloadIsAConflict() {
    for (FileScanStatus status :
        List.of(FileScanStatus.QUARANTINED, FileScanStatus.SCANNING, FileScanStatus.CLEAN)) {
      FileRecord record = recordWith(status);
      when(repository.findByIdAndTenantId(record.getId(), TENANT)).thenReturn(Optional.of(record));

      assertThatThrownBy(() -> service.downloadUrl(TENANT, record.getId()))
          .isInstanceOfSatisfying(
              AwapException.class,
              ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_SCAN_PENDING);
                assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
              });
    }
  }

  @Test
  void crossTenantLookupIsNotFound() {
    when(repository.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.downloadUrl(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND);
              assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
            });
  }

  private FileRecord promotedRecord() {
    FileRecord record = recordWith(FileScanStatus.QUARANTINED);
    record.beginScan();
    record.markClean();
    record.markPromoted("awap-files", "tenant/" + record.getId() + "/doc.pdf");
    return record;
  }

  private FileRecord recordWith(FileScanStatus target) {
    FileRecord record =
        new FileRecord(
            TENANT, "doc.pdf", "application/pdf", 10, "awap-quarantine", "tenant/file/doc.pdf");
    switch (target) {
      case QUARANTINED -> {
        // initial state
      }
      case SCANNING -> record.beginScan();
      case CLEAN -> {
        record.beginScan();
        record.markClean();
      }
      case SCAN_FAILED -> {
        record.beginScan();
        record.markScanFailed("timeout");
      }
      case INFECTED -> {
        record.beginScan();
        record.markInfected();
      }
      case PROMOTED -> {
        record.beginScan();
        record.markClean();
        record.markPromoted("awap-files", "tenant/file/doc.pdf");
      }
    }
    return record;
  }
}
