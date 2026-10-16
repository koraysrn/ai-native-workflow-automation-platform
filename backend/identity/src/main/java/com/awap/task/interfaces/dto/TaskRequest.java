package com.awap.task.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** Request to create a task. */
public record TaskRequest(
    @NotBlank(message = "title is required") String title,
    String description,
    UUID workflowInstanceId,
    UUID nodeExecutionId,
    UUID assigneeId) {}
