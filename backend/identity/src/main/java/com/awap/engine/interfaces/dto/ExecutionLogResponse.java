package com.awap.engine.interfaces.dto;

import java.time.Instant;
import java.util.UUID;

/** Public representation of an execution log entry. */
public record ExecutionLogResponse(
    UUID id,
    UUID workflowInstanceId,
    String nodeId,
    String nodeType,
    String action,
    String payload,
    Instant createdAt) {}
