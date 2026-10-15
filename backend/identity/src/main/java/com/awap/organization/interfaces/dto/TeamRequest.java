package com.awap.organization.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request to create a team. */
public record TeamRequest(
    @NotNull(message = "departmentId is required") UUID departmentId,
    @NotBlank(message = "name is required") String name) {}
