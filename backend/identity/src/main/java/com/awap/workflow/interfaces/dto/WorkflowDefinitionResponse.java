package com.awap.workflow.interfaces.dto;

import java.util.UUID;

/** Public representation of a workflow definition. */
public record WorkflowDefinitionResponse(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    String status,
    int currentVersion) {}
