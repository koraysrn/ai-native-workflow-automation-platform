package com.awap.organization.interfaces.dto;

import java.util.UUID;

/** Public representation of a team. */
public record TeamResponse(UUID id, UUID tenantId, UUID departmentId, String name) {}
