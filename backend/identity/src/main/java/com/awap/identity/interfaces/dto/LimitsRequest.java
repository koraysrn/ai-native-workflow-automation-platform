package com.awap.identity.interfaces.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Tenant plan and quota update request. */
public record LimitsRequest(
    @NotBlank(message = "plan is required") String plan,
    @Min(value = 1, message = "maxUsers must be at least 1") int maxUsers,
    @Min(value = 0, message = "maxWorkflows must not be negative") int maxWorkflows,
    @Min(value = 0, message = "maxAiCallsPerMonth must not be negative") long maxAiCallsPerMonth) {}
