package com.awap.form.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** Request to submit a form response. */
public record FormSubmissionRequest(
    @NotBlank(message = "dataJson is required") String dataJson,
    UUID workflowInstanceId,
    UUID nodeExecutionId) {}
