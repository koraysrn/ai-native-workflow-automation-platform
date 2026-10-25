package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

class FileServiceTest {

  private final FileRecordRepository repository = mock(FileRecordRepository.class);
  private final MinioClient minioClient = mock(MinioClient.class);
  private final ClamAvScanner clamAvScanner = mock(ClamAvScanner.class);
  private FileService service;

  @BeforeEach
  void setUp() {
    service = new FileService(repository, minioClient, clamAvScanner);
  }

  @Test
  void createUploadRejectsOversizedFile() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

    assertThatThrownBy(
            () -> service.createUpload(UUID.randomUUID(), "big.pdf", "application/pdf", 501L * 1024 * 1024))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("500 MB");
  }

  @Test
  void createUploadRejectsUnsupportedFormat() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

    assertThatThrownBy(
            () -> service.createUpload(UUID.randomUUID(), "malware.exe", "application/x-msdownload", 10L))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void createUploadReturnsPresignedSlot() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://minio/upload-url");

    FileService.UploadSlot slot =
        service.createUpload(UUID.randomUUID(), "doc.pdf", "application/pdf", 10L);

    assertThat(slot.uploadUrl()).isEqualTo("http://minio/upload-url");
    verify(repository).save(any(FileRecord.class));
    verify(minioClient).setBucketVersioning(any(SetBucketVersioningArgs.class));
  }

  @Test
  void downloadUrlThrowsNotFoundForMissingRecord() {
    when(repository.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.downloadUrl(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void downloadUrlRejectsInfectedFile() {
    FileRecord infected = new FileRecord(UUID.randomUUID(), "f.pdf", "application/pdf", 5, "b", "k");
    infected.markScanResult(FileScanStatus.INFECTED);
    when(repository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(infected));

    assertThatThrownBy(() -> service.downloadUrl(UUID.randomUUID(), infected.getId()))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("antivirus");
  }

  @Test
  void downloadUrlScansPendingFileBeforeReturningUrl() throws Exception {
    FileRecord pending = new FileRecord(UUID.randomUUID(), "f.pdf", "application/pdf", 5, "b", "k");
    when(repository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(pending));
    when(clamAvScanner.scan("b", "k")).thenReturn(FileScanStatus.CLEAN);
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://minio/download-url");

    String url = service.downloadUrl(UUID.randomUUID(), pending.getId());

    assertThat(url).isEqualTo("http://minio/download-url");
    assertThat(pending.getStatus()).isEqualTo(FileScanStatus.CLEAN);
  }

  @Test
  void minioDownFailsCleanlyOnBucketCheck() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(new RuntimeException("down"));

    assertThatThrownBy(() -> service.createUpload(UUID.randomUUID(), "a.pdf", "application/pdf", 1L))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("MinIO");
  }
}
