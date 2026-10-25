package com.awap.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Version metadata for a document whose bytes live in MinIO with bucket versioning enabled. */
@Entity
@Table(name = "document_versions")
public class DocumentVersion {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "file_id", nullable = false)
  private UUID fileId;

  @Column(nullable = false)
  private int version;

  @Column(nullable = false)
  private String bucket;

  @Column(name = "object_key", nullable = false)
  private String objectKey;

  @Column(nullable = false)
  private long size;

  @Column private String format;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DocumentStatus status;

  @Column(nullable = false)
  private boolean deleted;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected DocumentVersion() {}

  public DocumentVersion(
      UUID tenantId,
      UUID fileId,
      int version,
      String bucket,
      String objectKey,
      long size,
      String format) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.fileId = fileId;
    this.version = version;
    this.bucket = bucket;
    this.objectKey = objectKey;
    this.size = size;
    this.format = format;
    this.status = DocumentStatus.PENDING;
    this.deleted = false;
    this.createdAt = Instant.now();
  }

  public void markAvailable() {
    this.status = DocumentStatus.AVAILABLE;
  }

  public void archive() {
    this.status = DocumentStatus.ARCHIVED;
  }

  public void softDelete() {
    this.deleted = true;
    this.status = DocumentStatus.ARCHIVED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getFileId() {
    return fileId;
  }

  public int getVersion() {
    return version;
  }

  public String getBucket() {
    return bucket;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public long getSize() {
    return size;
  }

  public String getFormat() {
    return format;
  }

  public DocumentStatus getStatus() {
    return status;
  }

  public boolean isDeleted() {
    return deleted;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
