package com.awap.organization.interfaces.dto;

import java.util.List;
import java.util.UUID;

/** Department node of the organization hierarchy tree. */
public record DepartmentTreeResponse(UUID id, String name, List<TeamTreeResponse> teams) {}
