package com.awap.workflow.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** Request to create a workflow definition draft. */
public record WorkflowDefinitionRequest(
    @NotBlank(message = "name is required") String name,
    String description,
    @NotBlank(message = "definitionJson is required") String definitionJson) {}
