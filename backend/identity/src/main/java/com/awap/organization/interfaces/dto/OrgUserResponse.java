package com.awap.organization.interfaces.dto;

import java.util.UUID;

/** Public representation of an organization user. */
public record OrgUserResponse(
    UUID id, UUID tenantId, String email, String fullName, UUID teamId, UUID positionId) {}
