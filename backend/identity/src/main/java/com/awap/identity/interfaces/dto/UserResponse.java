package com.awap.identity.interfaces.dto;

import java.util.UUID;

/** Public representation of the authenticated user. */
public record UserResponse(UUID id, UUID tenantId, String email, String fullName, String role) {}
