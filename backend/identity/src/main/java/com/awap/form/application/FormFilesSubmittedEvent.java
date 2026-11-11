package com.awap.form.application;

import java.util.List;
import java.util.UUID;

/**
 * Audit/analytics event published through the transaction outbox when a submission references
 * scanned files (issue #200). It carries only metadata, never file contents.
 */
public record FormFilesSubmittedEvent(UUID submissionId, UUID formId, List<FileReference> files) {

  /** File metadata attached to the event. */
  public record FileReference(
      UUID fileId, String name, long size, String contentType, String scanStatus) {}
}
