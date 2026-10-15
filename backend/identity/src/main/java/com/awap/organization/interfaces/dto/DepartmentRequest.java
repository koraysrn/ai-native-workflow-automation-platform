package com.awap.organization.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request to create a department. */
public record DepartmentRequest(
    @NotNull(message = "companyId is required") UUID companyId,
    UUID parentId,
    @NotBlank(message = "name is required") String name) {}
