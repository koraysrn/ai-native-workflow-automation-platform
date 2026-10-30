package com.awap.engine.interfaces.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public representation of a workflow instance's saga and its ordered steps. */
public record SagaResponse(
    UUID id,
    UUID tenantId,
    UUID workflowInstanceId,
    String sagaKey,
    String status,
    String failureReason,
    Instant compensationStartedAt,
    Instant compensationCompletedAt,
    Instant createdAt,
    Instant updatedAt,
    List<SagaStepResponse> steps) {}
