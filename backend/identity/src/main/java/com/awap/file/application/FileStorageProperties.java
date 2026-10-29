package com.awap.file.application;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuration for the quarantine/promotion file pipeline. */
@Component
public class FileStorageProperties {

  static final long DEFAULT_MAX_SIZE_BYTES = 500L * 1024 * 1024;
  static final int MAX_PRESIGN_SECONDS = 300;

  private final String quarantineBucket;
  private final String promotedBucket;
  private final long maxSizeBytes;
  private final int presignTtlSeconds;
  private final Set<String> allowedExtensions;
  private final int maxScanAttempts;
  private final boolean deleteInfectedObjects;
  private final int quarantineRetentionDays;
  private final int promotedRetentionDays;
  private final int objectLockRetentionDays;

  @Autowired
  public FileStorageProperties(
      @Value("${awap.files.quarantine-bucket:awap-quarantine}") String quarantineBucket,
      @Value("${awap.files.promoted-bucket:awap-files}") String promotedBucket,
      @Value("${awap.files.max-size-bytes:524288000}") long maxSizeBytes,
      @Value("${awap.files.presign-ttl-seconds:300}") int presignTtlSeconds,
      @Value("${awap.files.allowed-extensions:pdf,docx,xlsx,pptx,png,jpg,jpeg,txt}")
          String allowedExtensions,
      @Value("${awap.files.max-scan-attempts:5}") int maxScanAttempts,
      @Value("${awap.files.infected-policy:DELETE}") String infectedPolicy,
      @Value("${awap.files.quarantine-retention-days:7}") int quarantineRetentionDays,
      @Value("${awap.files.promoted-retention-days:365}") int promotedRetentionDays,
      @Value("${awap.files.object-lock-retention-days:1}") int objectLockRetentionDays) {
    this.quarantineBucket = quarantineBucket;
    this.promotedBucket = promotedBucket;
    this.maxSizeBytes = maxSizeBytes > 0 ? maxSizeBytes : DEFAULT_MAX_SIZE_BYTES;
    this.presignTtlSeconds = clampPresign(presignTtlSeconds);
    this.allowedExtensions = parseExtensions(allowedExtensions);
    this.maxScanAttempts = Math.max(1, maxScanAttempts);
    this.deleteInfectedObjects = !"RETAIN".equalsIgnoreCase(infectedPolicy);
    this.quarantineRetentionDays = Math.max(1, quarantineRetentionDays);
    this.promotedRetentionDays = Math.max(1, promotedRetentionDays);
    this.objectLockRetentionDays = Math.max(1, objectLockRetentionDays);
  }

  /** Test/DI-friendly constructor with explicit values. */
  FileStorageProperties(
      String quarantineBucket,
      String promotedBucket,
      long maxSizeBytes,
      int presignTtlSeconds,
      Set<String> allowedExtensions,
      int maxScanAttempts,
      boolean deleteInfectedObjects) {
    this.quarantineBucket = quarantineBucket;
    this.promotedBucket = promotedBucket;
    this.maxSizeBytes = maxSizeBytes;
    this.presignTtlSeconds = clampPresign(presignTtlSeconds);
    this.allowedExtensions = Set.copyOf(allowedExtensions);
    this.maxScanAttempts = maxScanAttempts;
    this.deleteInfectedObjects = deleteInfectedObjects;
    this.quarantineRetentionDays = 7;
    this.promotedRetentionDays = 365;
    this.objectLockRetentionDays = 1;
  }

  private static int clampPresign(int seconds) {
    if (seconds <= 0) {
      return MAX_PRESIGN_SECONDS;
    }
    return Math.min(seconds, MAX_PRESIGN_SECONDS);
  }

  private static Set<String> parseExtensions(String configured) {
    if (configured == null || configured.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(configured.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  public String quarantineBucket() {
    return quarantineBucket;
  }

  public String promotedBucket() {
    return promotedBucket;
  }

  public long maxSizeBytes() {
    return maxSizeBytes;
  }

  public Duration presignTtl() {
    return Duration.ofSeconds(presignTtlSeconds);
  }

  public int presignTtlSeconds() {
    return presignTtlSeconds;
  }

  public Set<String> allowedExtensions() {
    return allowedExtensions;
  }

  public int maxScanAttempts() {
    return maxScanAttempts;
  }

  public boolean deleteInfectedObjects() {
    return deleteInfectedObjects;
  }

  public int quarantineRetentionDays() {
    return quarantineRetentionDays;
  }

  public int promotedRetentionDays() {
    return promotedRetentionDays;
  }

  public int objectLockRetentionDays() {
    return objectLockRetentionDays;
  }
}
