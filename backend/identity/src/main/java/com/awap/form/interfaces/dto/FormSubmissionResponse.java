package com.awap.form.interfaces.dto;

import java.time.Instant;
import java.util.UUID;

/** Public representation of a form submission. */
public record FormSubmissionResponse(
    UUID id,
    UUID formId,
    UUID workflowInstanceId,
    String dataJson,
    UUID submittedBy,
    Instant createdAt) {}
