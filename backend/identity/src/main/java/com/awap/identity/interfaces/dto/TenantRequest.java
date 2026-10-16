package com.awap.identity.interfaces.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Tenant on-boarding request, including the initial tenant admin account. */
public record TenantRequest(
    @NotBlank(message = "slug is required") String slug,
    @NotBlank(message = "name is required") String name,
    @NotBlank(message = "adminEmail is required")
        @Email(message = "adminEmail must be a valid email address")
        String adminEmail,
    @NotBlank(message = "adminPassword is required")
        @Size(min = 8, message = "adminPassword must be at least 8 characters")
        String adminPassword,
    @NotBlank(message = "adminFullName is required") String adminFullName) {}
