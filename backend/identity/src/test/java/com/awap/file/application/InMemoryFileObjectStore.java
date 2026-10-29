package com.awap.file.application;

import com.awap.file.domain.FileStorageException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double that behaves like the object-store port, including injected failures. */
final class InMemoryFileObjectStore implements FileObjectStore {

  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  private final List<String> copyCalls = new CopyOnWriteArrayList<>();
  private final List<String> deleteCalls = new CopyOnWriteArrayList<>();
  private final List<String> ensuredBuckets = new CopyOnWriteArrayList<>();
  private final List<Duration> presignTtls = new CopyOnWriteArrayList<>();
  private final Deque<RuntimeException> copyFailures = new ArrayDeque<>();
  private final Deque<RuntimeException> deleteFailures = new ArrayDeque<>();
  private RuntimeException openFailure;
  private RuntimeException ensureFailure;

  @Override
  public void ensureBucket(String bucket, boolean objectLock, int retentionDays) {
    if (ensureFailure != null) {
      throw ensureFailure;
    }
    ensuredBuckets.add(bucket + ":" + objectLock + ":" + retentionDays);
  }

  @Override
  public InputStream open(String bucket, String objectKey) {
    if (openFailure != null) {
      throw openFailure;
    }
    byte[] content = objects.get(id(bucket, objectKey));
    if (content == null) {
      throw new FileStorageException("Missing object " + bucket + "/" + objectKey);
    }
    return new ByteArrayInputStream(content);
  }

  @Override
  public boolean exists(String bucket, String objectKey) {
    return objects.containsKey(id(bucket, objectKey));
  }

  @Override
  public void copy(
      String sourceBucket, String sourceObjectKey, String targetBucket, String targetObjectKey) {
    copyCalls.add(
        sourceBucket + "/" + sourceObjectKey + "->" + targetBucket + "/" + targetObjectKey);
    if (!copyFailures.isEmpty()) {
      throw copyFailures.removeFirst();
    }
    byte[] source = objects.get(id(sourceBucket, sourceObjectKey));
    if (source == null) {
      throw new FileStorageException("Missing source " + sourceBucket + "/" + sourceObjectKey);
    }
    objects.put(id(targetBucket, targetObjectKey), source.clone());
  }

  @Override
  public void delete(String bucket, String objectKey) {
    deleteCalls.add(bucket + "/" + objectKey);
    if (!deleteFailures.isEmpty()) {
      throw deleteFailures.removeFirst();
    }
    objects.remove(id(bucket, objectKey));
  }

  @Override
  public String presignedPut(String bucket, String objectKey, Duration ttl) {
    presignTtls.add(ttl);
    return "https://minio/" + bucket + "/" + objectKey + "?method=PUT&ttl=" + ttl.toSeconds();
  }

  @Override
  public String presignedGet(String bucket, String objectKey, Duration ttl) {
    presignTtls.add(ttl);
    return "https://minio/" + bucket + "/" + objectKey + "?method=GET&ttl=" + ttl.toSeconds();
  }

  void put(String bucket, String objectKey, byte[] content) {
    objects.put(id(bucket, objectKey), content.clone());
  }

  void failCopyNext(RuntimeException failure) {
    copyFailures.addLast(failure);
  }

  void failDeleteNext(RuntimeException failure) {
    deleteFailures.addLast(failure);
  }

  void failOpen(RuntimeException failure) {
    this.openFailure = failure;
  }

  void failEnsure(RuntimeException failure) {
    this.ensureFailure = failure;
  }

  void clearOpenFailure() {
    this.openFailure = null;
  }

  List<String> copyCalls() {
    return List.copyOf(copyCalls);
  }

  List<String> deleteCalls() {
    return List.copyOf(deleteCalls);
  }

  List<String> ensuredBuckets() {
    return List.copyOf(ensuredBuckets);
  }

  List<Duration> presignTtls() {
    return new ArrayList<>(presignTtls);
  }

  private String id(String bucket, String objectKey) {
    return bucket + "/" + objectKey;
  }
}
