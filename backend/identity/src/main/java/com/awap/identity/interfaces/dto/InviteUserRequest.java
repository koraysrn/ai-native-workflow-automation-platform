package com.awap.identity.interfaces.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request to invite a user to a tenant with a role. */
public record InviteUserRequest(
    @NotBlank(message = "email is required") @Email(message = "email must be valid") String email,
    @NotBlank(message = "role is required")
        @Pattern(
            regexp = "TENANT_ADMIN|MANAGER|EMPLOYEE|SYSTEM_ADMIN",
            message = "role must be one of TENANT_ADMIN, MANAGER, EMPLOYEE, SYSTEM_ADMIN")
        String role) {}
