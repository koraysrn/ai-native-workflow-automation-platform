package com.awap.identity.interfaces.dto;

import java.time.Instant;
import java.util.UUID;

/** Public representation of an invitation, including the one-time raw token. */
public record InvitationResponse(
    UUID id, UUID tenantId, String email, String role, Instant expiresAt, String token) {}
