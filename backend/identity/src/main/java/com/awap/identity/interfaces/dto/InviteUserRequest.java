package com.awap.identity.interfaces.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request to invite a user to a tenant with a role. */
public record InviteUserRequest(
    @NotBlank(message = "email is required") @Email(message = "email must be valid") String email,
    @NotBlank(message = "role is required") String role) {}
