package com.awap.organization.interfaces.dto;

import java.util.List;
import java.util.UUID;

/** Company node of the organization hierarchy tree. */
public record CompanyTreeResponse(UUID id, String name, List<DepartmentTreeResponse> departments) {}
