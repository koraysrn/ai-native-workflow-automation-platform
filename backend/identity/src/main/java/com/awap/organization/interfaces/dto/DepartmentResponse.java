package com.awap.organization.interfaces.dto;

import java.util.UUID;

/** Public representation of a department. */
public record DepartmentResponse(
    UUID id, UUID tenantId, UUID companyId, UUID parentId, String name) {}
