package com.awap.identity.interfaces.dto;

import java.util.UUID;

/** Tenant membership of the authenticated user. */
public record TenantMembershipResponse(UUID tenantId, String role) {}
