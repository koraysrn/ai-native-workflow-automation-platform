package com.awap.task.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request to assign a task to a user. */
public record TaskAssignRequest(@NotNull(message = "assigneeId is required") UUID assigneeId) {}
