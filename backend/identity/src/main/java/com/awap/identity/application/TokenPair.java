package com.awap.identity.application;

/** Access and refresh token pair issued after successful authentication. */
public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}
