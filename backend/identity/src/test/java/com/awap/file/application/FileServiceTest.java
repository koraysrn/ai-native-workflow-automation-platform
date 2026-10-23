package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.infrastructure.FileRecordRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.SetBucketVersioningArgs;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the file/document use cases: size/format limits, versioning and scan gate. */
@ExtendWith(MockitoExtension.class)
class FileServiceTest {

  @Mock private FileRecordRepository repository;
  @Mock private MinioClient minioClient;
  @Mock private ClamAvScanner clamAvScanner;

  private FileService service;
  private final UUID tenantId = UUID.randomUUID();
  private final UUID fileId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new FileService(repository, minioClient, clamAvScanner);
  }

  @Test
  void createUploadRejectsOversizeFile() throws Exception {
    long overLimit = 500L * 1024 * 1024 + 1;

    assertThatThrownBy(
            () -> service.createUpload(tenantId, "huge.pdf", "application/pdf", overLimit))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void createUploadRejectsUnsupportedFormat() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

    assertThatThrownBy(
            () -> service.createUpload(tenantId, "malware.exe", "application/octet-stream", 10))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void createUploadAcceptsSupportedFormatAndReturnsSlot() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://presigned-upload");

    FileService.UploadSlot slot =
        service.createUpload(tenantId, "report.pdf", "application/pdf", 1024);

    assertThat(slot.fileId()).isNotNull();
    assertThat(slot.uploadUrl()).isEqualTo("http://presigned-upload");
  }

  @Test
  void createUploadEnablesBucketVersioning() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://presigned-upload");

    service.createUpload(tenantId, "report.pdf", "application/pdf", 1024);

    verify(minioClient).setBucketVersioning(any(SetBucketVersioningArgs.class));
  }

  @Test
  void downloadUrlScansPendingFileAndBlocksInfected() throws Exception {
    FileRecord record = new FileRecord(tenantId, "a.pdf", "application/pdf", 10, "b", "k");
    when(repository.findByIdAndTenantId(fileId, tenantId)).thenReturn(Optional.of(record));
    when(clamAvScanner.scan(record.getBucket(), record.getObjectKey()))
        .thenReturn(FileScanStatus.INFECTED);

    assertThatThrownBy(() -> service.downloadUrl(tenantId, fileId))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void downloadUrlAllowsCleanFile() throws Exception {
    FileRecord record = new FileRecord(tenantId, "a.pdf", "application/pdf", 10, "b", "k");
    record.markScanResult(FileScanStatus.CLEAN);
    when(repository.findByIdAndTenantId(fileId, tenantId)).thenReturn(Optional.of(record));
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://presigned-download");

    String url = service.downloadUrl(tenantId, fileId);

    assertThat(url).isEqualTo("http://presigned-download");
  }

  @Test
  void downloadUrlRejectsUnknownFile() {
    when(repository.findByIdAndTenantId(fileId, tenantId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.downloadUrl(tenantId, fileId))
        .isInstanceOf(AwapException.class);
  }
}
