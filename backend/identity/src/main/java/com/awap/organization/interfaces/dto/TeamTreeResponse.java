package com.awap.organization.interfaces.dto;

import java.util.List;
import java.util.UUID;

/** Team node of the organization hierarchy tree. */
public record TeamTreeResponse(UUID id, String name, List<OrgUserResponse> users) {}
