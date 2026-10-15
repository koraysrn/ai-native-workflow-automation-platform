package com.awap.engine.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request to start a workflow instance from a published definition. */
public record StartWorkflowRequest(
    @NotNull(message = "workflowDefinitionId is required") UUID workflowDefinitionId,
    String idempotencyKey,
    String contextJson) {}
