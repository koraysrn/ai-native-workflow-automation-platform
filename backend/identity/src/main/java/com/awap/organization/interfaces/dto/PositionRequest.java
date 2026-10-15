package com.awap.organization.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** Request to create a position. */
public record PositionRequest(@NotBlank(message = "name is required") String name) {}
