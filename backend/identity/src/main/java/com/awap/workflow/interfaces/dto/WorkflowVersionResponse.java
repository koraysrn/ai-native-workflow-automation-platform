package com.awap.workflow.interfaces.dto;

import java.time.Instant;
import java.util.UUID;

/** Public representation of a workflow version snapshot. */
public record WorkflowVersionResponse(
    UUID id, int version, String definitionJson, String status, Instant createdAt) {}
