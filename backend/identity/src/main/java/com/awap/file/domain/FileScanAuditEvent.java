package com.awap.file.domain;

/** Immutable audit events emitted by the quarantine/promotion pipeline. */
public enum FileScanAuditEvent {
  SCAN_FAILED,
  INFECTED,
  PROMOTE_FAILED,
  PROMOTED
}
