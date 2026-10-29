package com.awap.file.application;

import com.awap.file.domain.FileStorageException;
import java.io.InputStream;
import java.time.Duration;

/**
 * Port for the object store used by the file module. Quarantine uploads, scan streaming,
 * server-side promotion copies and presigned URLs go through this boundary so the domain logic can
 * be exercised against a fake adapter when MinIO is not available.
 */
public interface FileObjectStore {

  /** Creates the bucket when missing and applies versioning/object-lock/lifecycle settings. */
  void ensureBucket(String bucket, boolean objectLock, int retentionDays);

  /** Opens a streaming read handle; callers must close it. */
  InputStream open(String bucket, String objectKey) throws FileStorageException;

  /** Returns whether the object currently exists (used to make promotion retries safe). */
  boolean exists(String bucket, String objectKey);

  /** Server-side copy (no bytes pass through the JVM). */
  void copy(
      String sourceBucket, String sourceObjectKey, String targetBucket, String targetObjectKey);

  /** Deletes one object (including locked governance objects for infected cleanup). */
  void delete(String bucket, String objectKey);

  String presignedPut(String bucket, String objectKey, Duration ttl);

  String presignedGet(String bucket, String objectKey, Duration ttl);
}
