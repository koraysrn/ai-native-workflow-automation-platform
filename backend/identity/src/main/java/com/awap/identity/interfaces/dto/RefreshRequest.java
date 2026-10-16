package com.awap.identity.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body carrying a refresh token for rotation or logout. */
public record RefreshRequest(@NotBlank(message = "refreshToken is required") String refreshToken) {}
