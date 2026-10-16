package com.awap.file.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.domain.FileRecord;
import com.awap.file.infrastructure.FileRecordRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** File use cases: create an upload slot (presigned PUT) and obtain a download URL. */
@Service
public class FileService {

  private static final String BUCKET = "awap-files";

  private final FileRecordRepository repository;
  private final MinioClient minioClient;

  public FileService(FileRecordRepository repository, MinioClient minioClient) {
    this.repository = repository;
    this.minioClient = minioClient;
  }

  @Transactional
  public UploadSlot createUpload(UUID tenantId, String name, String contentType, long size) {
    ensureBucket();
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
    return presignedUrl(record.getObjectKey(), Method.GET);
  }

  private void ensureBucket() {
    try {
      if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
      }
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

  private String sanitize(String name) {
    return name == null ? "file" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  /** Presigned upload slot returned to the client. */
  public record UploadSlot(UUID fileId, String uploadUrl) {}
}
