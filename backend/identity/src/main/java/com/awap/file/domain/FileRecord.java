package com.awap.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** Metadata for a file stored in MinIO, with an antivirus scan status. */
@Entity
@Table(name = "file_records")
public class FileRecord {

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
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.name = name;
    this.contentType = contentType;
    this.size = size;
    this.bucket = bucket;
    this.objectKey = objectKey;
    this.status = FileScanStatus.PENDING;
    this.createdAt = Instant.now();
  }

  public void markScanResult(FileScanStatus status) {
    this.status = status;
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

  public Instant getCreatedAt() {
    return createdAt;
  }
}
