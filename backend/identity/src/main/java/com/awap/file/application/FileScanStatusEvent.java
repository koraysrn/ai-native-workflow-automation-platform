package com.awap.file.application;

import com.awap.file.domain.FileRecord;
import com.awap.file.domain.FileScanStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, tenant-scoped notification that the antivirus scan status of one file changed.
 *
 * <p>The event intentionally carries the (sanitized) display name so the document UI can update a
 * row in place. Subscribers must always be scoped by {@link #tenantId()}; the event never leaves
 * the in-process bus and is not persisted.
 */
public record FileScanStatusEvent(
    UUID tenantId,
    UUID fileId,
    String name,
    FileScanStatus status,
    String detail,
    Instant occurredAt) {

  public FileScanStatusEvent {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (fileId == null) {
      throw new IllegalArgumentException("fileId is required");
    }
    if (status == null) {
      throw new IllegalArgumentException("status is required");
    }
    detail = detail == null ? "" : detail;
    occurredAt = occurredAt == null ? Instant.now() : occurredAt;
  }

  /** Builds the event for the current status of {@code record}. */
  public static FileScanStatusEvent of(FileRecord record, String detail) {
    return new FileScanStatusEvent(
        record.getTenantId(),
        record.getId(),
        record.getName(),
        record.getStatus(),
        detail,
        Instant.now());
  }

  /** Builds an event for a specific status without mutating the record. */
  public static FileScanStatusEvent of(FileRecord record, FileScanStatus status, String detail) {
    return new FileScanStatusEvent(
        record.getTenantId(), record.getId(), record.getName(), status, detail, Instant.now());
  }
}
