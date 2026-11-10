package com.awap.organization.interfaces.dto;

import com.awap.identity.domain.Role;
import jakarta.validation.constraints.NotNull;

/** Request to change the role attached to a position. */
public record PositionRoleRequest(@NotNull(message = "role is required") Role role) {}
