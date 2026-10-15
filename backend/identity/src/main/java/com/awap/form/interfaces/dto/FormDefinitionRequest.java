package com.awap.form.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** Request to create a form definition. */
public record FormDefinitionRequest(
    @NotBlank(message = "name is required") String name,
    @NotBlank(message = "schemaJson is required") String schemaJson) {}
