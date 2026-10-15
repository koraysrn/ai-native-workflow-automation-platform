package com.awap.identity.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** Credentials submitted by a user to sign in to a tenant. */
public record LoginRequest(
    @NotBlank(message = "tenantSlug is required") String tenantSlug,
    @NotBlank(message = "email is required") String email,
    @NotBlank(message = "password is required") String password) {}
