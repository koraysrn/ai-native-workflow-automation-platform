package com.awap.engine.interfaces.dto;

import java.time.Instant;
import java.util.UUID;

/** Public, tenant-scoped representation of one saga step. */
public record SagaStepResponse(
    UUID id,
    int stepOrder,
    String nodeId,
    String nodeType,
    String actionType,
    String compensationType,
    String status,
    int attempts,
    int maxAttempts,
    long retryBackoffMs,
    int timeoutSeconds,
    Instant nextAttemptAt,
    String lastError,
    Instant compensatedAt) {}
