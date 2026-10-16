package com.awap.engine.application;

import java.util.UUID;

/** Raised when an external actor (form submit / task decision) completes a waiting node. */
public record NodeCompletedEvent(
    UUID tenantId, UUID workflowInstanceId, UUID nodeExecutionId, String outputJson) {}
