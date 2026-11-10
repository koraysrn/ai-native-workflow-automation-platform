package com.awap.organization.interfaces.dto;

import com.awap.identity.domain.Role;
import jakarta.validation.constraints.NotBlank;

/** Request to create a position; a missing role defaults to EMPLOYEE. */
public record PositionRequest(@NotBlank(message = "name is required") String name, Role role) {}
