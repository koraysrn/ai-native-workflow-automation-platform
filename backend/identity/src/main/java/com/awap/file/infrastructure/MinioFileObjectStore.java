package com.awap.file.infrastructure;

import com.awap.file.application.FileObjectStore;
import com.awap.file.domain.FileStorageException;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.SetObjectLockConfigurationArgs;
import io.minio.http.Method;
import io.minio.messages.AbortIncompleteMultipartUpload;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.NoncurrentVersionExpiration;
import io.minio.messages.ObjectLockConfiguration;
import io.minio.messages.RetentionDurationDays;
import io.minio.messages.RetentionMode;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import io.minio.messages.VersioningConfiguration;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MinIO-backed {@link FileObjectStore}. Quarantine uses object-lock + versioning, promoted usage is
 * versioned too, and both buckets receive lifecycle rules so abandoned uploads/quarantine objects
 * do not accumulate forever.
 */
@Component
public class MinioFileObjectStore implements FileObjectStore {

  private static final Logger log = LoggerFactory.getLogger(MinioFileObjectStore.class);

  private final MinioClient minioClient;

  public MinioFileObjectStore(MinioClient minioClient) {
    this.minioClient = minioClient;
  }

  @Override
  public void ensureBucket(String bucket, boolean objectLock, int retentionDays) {
    try {
      if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        minioClient.makeBucket(
            MakeBucketArgs.builder().bucket(bucket).objectLock(objectLock).build());
      }
      minioClient.setBucketVersioning(
          SetBucketVersioningArgs.builder()
              .bucket(bucket)
              .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null))
              .build());
      if (objectLock) {
        enableObjectLock(bucket, retentionDays);
      }
      applyLifecycle(bucket, retentionDays);
    } catch (Exception ex) {
      throw new FileStorageException("Could not provision bucket " + bucket, ex);
    }
  }

  private void enableObjectLock(String bucket, int retentionDays) {
    try {
      minioClient.setObjectLockConfiguration(
          SetObjectLockConfigurationArgs.builder()
              .bucket(bucket)
              .config(
                  new ObjectLockConfiguration(
                      RetentionMode.GOVERNANCE, new RetentionDurationDays(retentionDays)))
              .build());
    } catch (Exception ex) {
      // Pre-existing buckets created without object lock cannot be upgraded in place; keep the
      // pipeline working (versioning still protects history) and surface the misconfiguration.
      log.warn("Object lock could not be enabled on bucket {}: {}", bucket, ex.getMessage());
    }
  }

  private void applyLifecycle(String bucket, int expireDays) throws Exception {
    LifecycleRule rule =
        new LifecycleRule(
            Status.ENABLED,
            new AbortIncompleteMultipartUpload(1),
            new Expiration((ZonedDateTime) null, expireDays, false),
            new RuleFilter(""),
            "awap-expire-" + bucket,
            new NoncurrentVersionExpiration(expireDays),
            null,
            null);
    minioClient.setBucketLifecycle(
        SetBucketLifecycleArgs.builder()
            .bucket(bucket)
            .config(new LifecycleConfiguration(List.of(rule)))
            .build());
  }

  @Override
  public InputStream open(String bucket, String objectKey) {
    try {
      return minioClient.getObject(
          GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    } catch (Exception ex) {
      throw new FileStorageException("Could not read object " + bucket + "/" + objectKey, ex);
    }
  }

  @Override
  public boolean exists(String bucket, String objectKey) {
    try (InputStream ignored =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  @Override
  public void copy(
      String sourceBucket, String sourceObjectKey, String targetBucket, String targetObjectKey) {
    try {
      minioClient.copyObject(
          CopyObjectArgs.builder()
              .bucket(targetBucket)
              .object(targetObjectKey)
              .source(CopySource.builder().bucket(sourceBucket).object(sourceObjectKey).build())
              .build());
    } catch (Exception ex) {
      throw new FileStorageException(
          "Could not copy object " + sourceBucket + "/" + sourceObjectKey, ex);
    }
  }

  @Override
  public void delete(String bucket, String objectKey) {
    try {
      // Empty quarantine bucket cleanup must also work for governance-locked objects.
      minioClient.removeObject(
          RemoveObjectArgs.builder()
              .bucket(bucket)
              .object(objectKey)
              .bypassGovernanceMode(true)
              .build());
    } catch (Exception ex) {
      throw new FileStorageException("Could not delete object " + bucket + "/" + objectKey, ex);
    }
  }

  @Override
  public String presignedPut(String bucket, String objectKey, Duration ttl) {
    return presign(bucket, objectKey, ttl, Method.PUT);
  }

  @Override
  public String presignedGet(String bucket, String objectKey, Duration ttl) {
    return presign(bucket, objectKey, ttl, Method.GET);
  }

  private String presign(String bucket, String objectKey, Duration ttl, Method method) {
    try {
      return minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(method)
              .bucket(bucket)
              .object(objectKey)
              .expiry((int) Math.max(1, ttl.toSeconds()), TimeUnit.SECONDS)
              .build());
    } catch (Exception ex) {
      throw new FileStorageException(
          "Could not presign " + method + " for " + bucket + "/" + objectKey, ex);
    }
  }
}
