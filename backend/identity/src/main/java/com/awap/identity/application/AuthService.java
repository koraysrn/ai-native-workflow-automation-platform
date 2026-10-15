package com.awap.identity.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.domain.RefreshToken;
import com.awap.identity.domain.Tenant;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.RefreshTokenRepository;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.identity.infrastructure.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authentication use cases: login, refresh rotation, logout and current user lookup. */
@Service
public class AuthService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final TenantRepository tenantRepository;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtService jwtService;
  private final PasswordEncoder passwordEncoder;

  public AuthService(
      TenantRepository tenantRepository,
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      JwtService jwtService,
      PasswordEncoder passwordEncoder) {
    this.tenantRepository = tenantRepository;
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.jwtService = jwtService;
    this.passwordEncoder = passwordEncoder;
  }

  public TokenPair login(String tenantSlug, String email, String password) {
    Tenant tenant = tenantRepository.findBySlug(tenantSlug).orElseThrow(this::invalidCredentials);
    User user =
        userRepository
            .findByTenantIdAndEmail(tenant.getId(), email)
            .orElseThrow(this::invalidCredentials);
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw invalidCredentials();
    }
    return issueTokens(user);
  }

  @Transactional
  public TokenPair refresh(String rawRefreshToken) {
    RefreshToken stored =
        refreshTokenRepository
            .findByTokenHash(hashToken(rawRefreshToken))
            .filter(token -> !token.isRevoked())
            .filter(token -> token.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(this::invalidRefreshToken);
    stored.revoke();
    User user = userRepository.findById(stored.getUserId()).orElseThrow(this::invalidRefreshToken);
    return issueTokens(user);
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return;
    }
    refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken)).ifPresent(RefreshToken::revoke);
  }

  public User me(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "User not found."));
  }

  private TokenPair issueTokens(User user) {
    String accessToken =
        jwtService.generateAccessToken(
            user.getId(), user.getTenantId(), user.getEmail(), user.getRole());
    String refreshToken = generateOpaqueToken();
    RefreshToken entity =
        new RefreshToken(
            user.getTenantId(),
            user.getId(),
            hashToken(refreshToken),
            Instant.now().plus(jwtService.refreshTokenTtl()));
    refreshTokenRepository.save(entity);
    return new TokenPair(accessToken, refreshToken, jwtService.accessTokenTtl().toSeconds());
  }

  private String generateOpaqueToken() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private AwapException invalidCredentials() {
    return new AwapException(
        ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Invalid credentials.");
  }

  private AwapException invalidRefreshToken() {
    return new AwapException(
        ErrorCode.REFRESH_TOKEN_INVALID,
        HttpStatus.UNAUTHORIZED,
        "Refresh token is invalid or expired.");
  }
}
