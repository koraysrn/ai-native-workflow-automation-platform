package com.awap.file.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import com.awap.file.infrastructure.FileRecordRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketVersioningArgs;
import io.minio.http.Method;
import io.minio.messages.VersioningConfiguration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** File use cases: create an upload slot (presigned PUT) and obtain a download URL. */
@Service
public class FileService {

  private static final String BUCKET = "awap-files";
  private static final long MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024;
  private static final Set<String> ALLOWED_EXTENSIONS =
      Set.of("pdf", "docx", "xlsx", "pptx", "png", "jpg", "jpeg", "txt");

  private final FileRecordRepository repository;
  private final MinioClient minioClient;
  private final ClamAvScanner clamAvScanner;

  public FileService(
      FileRecordRepository repository, MinioClient minioClient, ClamAvScanner clamAvScanner) {
    this.repository = repository;
    this.minioClient = minioClient;
    this.clamAvScanner = clamAvScanner;
  }

  @Transactional
  public UploadSlot createUpload(UUID tenantId, String name, String contentType, long size) {
    ensureBucket();
    validateSize(size);
    validateFormat(name);
    String objectKey = tenantId + "/" + UUID.randomUUID() + "-" + sanitize(name);
    FileRecord record = new FileRecord(tenantId, name, contentType, size, BUCKET, objectKey);
    repository.save(record);
    String uploadUrl = presignedUrl(objectKey, Method.PUT);
    return new UploadSlot(record.getId(), uploadUrl);
  }

  public String downloadUrl(UUID tenantId, UUID id) {
    FileRecord record =
        repository
            .findByIdAndTenantId(id, tenantId)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "File not found."));

    if (record.getStatus() == FileScanStatus.PENDING) {
      FileScanStatus result = clamAvScanner.scan(record.getBucket(), record.getObjectKey());
      record.markScanResult(result);
    }
    if (record.getStatus() == FileScanStatus.INFECTED) {
      throw new AwapException(
          ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "File was rejected by antivirus scanning.");
    }
    return presignedUrl(record.getObjectKey(), Method.GET);
  }

  private void ensureBucket() {
    try {
      if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
      }
      minioClient.setBucketVersioning(
          SetBucketVersioningArgs.builder()
              .bucket(BUCKET)
              .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null))
              .build());
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.INTERNAL_SERVER_ERROR,
          "MinIO is not reachable.");
    }
  }

  private String presignedUrl(String objectKey, Method method) {
    try {
      return minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(method)
              .bucket(BUCKET)
              .object(objectKey)
              .expiry(15, TimeUnit.MINUTES)
              .build());
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Could not generate presigned URL.");
    }
  }

  private void validateSize(long size) {
    if (size > MAX_FILE_SIZE_BYTES) {
      throw new AwapException(
          ErrorCode.FILE_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the 500 MB limit.");
    }
  }

  private void validateFormat(String name) {
    if (!ALLOWED_EXTENSIONS.contains(extensionOf(name))) {
      throw new AwapException(
          ErrorCode.FILE_FORMAT_UNSUPPORTED,
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "File format is not supported.");
    }
  }

  private String extensionOf(String name) {
    if (name == null) {
      return "";
    }
    int dot = name.lastIndexOf('.');
    return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase() : "";
  }

  private String sanitize(String name) {
    return name == null ? "file" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  /** Presigned upload slot returned to the client. */
  public record UploadSlot(UUID fileId, String uploadUrl) {}
}
