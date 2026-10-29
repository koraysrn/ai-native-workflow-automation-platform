package com.awap.workflow.interfaces.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of a workflow version snapshot. {@code status} is the publish state and
 * {@code current} marks the version the definition currently resolves to; {@code archived} is true
 * once the snapshot has been soft-deleted and can no longer be selected.
 */
public record WorkflowVersionResponse(
    UUID id,
    int version,
    String definitionJson,
    String status,
    boolean archived,
    boolean current,
    Instant createdAt) {}
