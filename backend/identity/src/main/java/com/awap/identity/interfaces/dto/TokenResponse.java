package com.awap.identity.interfaces.dto;

/** Token response returned after login or refresh. */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {}
