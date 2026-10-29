package com.awap.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable audit row for security-relevant file scan/promotion outcomes. */
@Entity
@Table(name = "file_scan_audit")
public class FileScanAudit {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "file_id", nullable = false)
  private UUID fileId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event", nullable = false)
  private FileScanAuditEvent event;

  @Column(name = "detail", nullable = false)
  private String detail;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected FileScanAudit() {}

  public FileScanAudit(UUID tenantId, UUID fileId, FileScanAuditEvent event, String detail) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.fileId = fileId;
    this.event = event;
    String value = detail == null ? "" : detail;
    this.detail = value.length() <= 500 ? value : value.substring(0, 500);
    this.createdAt = Instant.now();
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

  public FileScanAuditEvent getEvent() {
    return event;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
