package com.awap.engine.application;

import java.util.UUID;

/** Raised when a SUB_PROCESS child instance reaches a terminal state. */
public record SubProcessCompletedEvent(
    UUID tenantId,
    UUID parentInstanceId,
    String parentNodeId,
    UUID childInstanceId,
    boolean success) {}
