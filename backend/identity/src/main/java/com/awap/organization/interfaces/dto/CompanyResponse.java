package com.awap.organization.interfaces.dto;

import java.util.UUID;

/** Public representation of a company. */
public record CompanyResponse(UUID id, UUID tenantId, String name) {}
