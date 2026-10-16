package com.awap.engine.interfaces.dto;

import java.time.Instant;
import java.util.UUID;

/** Public representation of a workflow instance. */
public record WorkflowInstanceResponse(
    UUID id,
    UUID tenantId,
    UUID workflowDefinitionId,
    int workflowVersion,
    String state,
    String contextJson,
    String idempotencyKey,
    Instant createdAt) {}
