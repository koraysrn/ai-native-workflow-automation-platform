package com.awap.organization.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request to assign an organization user to a team. */
public record TeamAssignRequest(@NotNull(message = "teamId is required") UUID teamId) {}
