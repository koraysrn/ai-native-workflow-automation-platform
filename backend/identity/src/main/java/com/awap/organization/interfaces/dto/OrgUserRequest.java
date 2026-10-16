package com.awap.organization.interfaces.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** Request to create an organization user. */
public record OrgUserRequest(
    @NotBlank(message = "email is required") @Email(message = "email must be a valid email address")
        String email,
    @NotBlank(message = "fullName is required") String fullName,
    UUID teamId,
    UUID positionId) {}
