package com.awap.organization.interfaces.dto;

import com.awap.identity.domain.Role;
import java.util.UUID;

/** Public representation of a position. */
public record PositionResponse(UUID id, UUID tenantId, String name, Role role) {}
