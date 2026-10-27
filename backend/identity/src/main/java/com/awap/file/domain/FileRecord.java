package com.awap.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/**
 * Metadata for a file uploaded into the quarantine bucket, with a guarded antivirus/promotion
 * lifecycle. The record never leaves {@link FileScanStatus#PROMOTED} until the bytes were copied
 * into the promoted bucket and the quarantine copy was deleted.
 */
@Entity
@Table(name = "file_records")
public class FileRecord {

  private static final int MAX_ERROR_LENGTH = 500;

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String name;

  @Column(name = "content_type")
  private String contentType;

  @Column(nullable = false)
  private long size;

  @Column(name = "bucket", nullable = false)
  private String bucket;

  @Column(name = "object_key", nullable = false)
  private String objectKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private FileScanStatus status;

  @Column(name = "promoted_bucket")
  private String promotedBucket;

  @Column(name = "promoted_object_key")
  private String promotedObjectKey;

  @Column(name = "promoted_at")
  private Instant promotedAt;

  @Column(name = "scan_attempts", nullable = false)
  private int scanAttempts;

  @Column(name = "last_scan_error")
  private String lastScanError;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(nullable = false)
  private Instant createdAt;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  @LastModifiedBy
  @Column(name = "updated_by")
  private UUID updatedBy;

  protected FileRecord() {}

  public FileRecord(
      UUID tenantId, String name, String contentType, long size, String bucket, String objectKey) {
    this(UUID.randomUUID(), tenantId, name, contentType, size, bucket, objectKey);
  }

  public FileRecord(
      UUID id,
      UUID tenantId,
      String name,
      String contentType,
      long size,
      String bucket,
      String objectKey) {
    this.id = id;
    this.tenantId = tenantId;
    this.name = name;
    this.contentType = contentType;
    this.size = size;
    this.bucket = bucket;
    this.objectKey = objectKey;
    this.status = FileScanStatus.QUARANTINED;
    this.scanAttempts = 0;
    this.createdAt = Instant.now();
  }

  /** Starts a scan attempt. Allowed from QUARANTINED and from SCAN_FAILED (retry). */
  public void beginScan() {
    this.status = this.status.transitionTo(FileScanStatus.SCANNING);
    this.scanAttempts = this.scanAttempts + 1;
  }

  /** Records a clean ClamAV verdict. Does not promote; promotion is a separate guarded step. */
  public void markClean() {
    this.status = this.status.transitionTo(FileScanStatus.CLEAN);
    this.lastScanError = null;
  }

  /** Marks the object as malware. Infected objects are never promoted. */
  public void markInfected() {
    this.status = this.status.transitionTo(FileScanStatus.INFECTED);
  }

  /** Records a transient scan failure so the scheduler can retry without an infinite state. */
  public void markScanFailed(String reason) {
    this.status = this.status.transitionTo(FileScanStatus.SCAN_FAILED);
    this.lastScanError = truncate(reason);
  }

  /** Records a successful, idempotent promotion to the promoted bucket. */
  public void markPromoted(String promotedBucket, String promotedObjectKey) {
    this.status = this.status.transitionTo(FileScanStatus.PROMOTED);
    this.promotedBucket = promotedBucket;
    this.promotedObjectKey = promotedObjectKey;
    this.promotedAt = Instant.now();
  }

  public boolean canRetryScan(int maxAttempts) {
    return status.isScannable() && scanAttempts < maxAttempts;
  }

  private String truncate(String reason) {
    if (reason == null) {
      return "";
    }
    return reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getName() {
    return name;
  }

  public String getContentType() {
    return contentType;
  }

  public long getSize() {
    return size;
  }

  public String getBucket() {
    return bucket;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public FileScanStatus getStatus() {
    return status;
  }

  public String getPromotedBucket() {
    return promotedBucket;
  }

  public String getPromotedObjectKey() {
    return promotedObjectKey;
  }

  public Instant getPromotedAt() {
    return promotedAt;
  }

  public int getScanAttempts() {
    return scanAttempts;
  }

  public String getLastScanError() {
    return lastScanError;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
