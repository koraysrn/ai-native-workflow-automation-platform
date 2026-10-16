package com.awap.task.interfaces.dto;

import java.util.UUID;

/** Public representation of a task. */
public record TaskResponse(
    UUID id,
    UUID tenantId,
    UUID workflowInstanceId,
    String title,
    String description,
    UUID assigneeId,
    String status,
    long version) {}
