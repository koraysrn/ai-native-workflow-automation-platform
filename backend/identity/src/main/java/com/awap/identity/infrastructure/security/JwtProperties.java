package com.awap.identity.infrastructure.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT configuration bound from {@code awap.security.jwt}. */
@ConfigurationProperties(prefix = "awap.security.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {}
