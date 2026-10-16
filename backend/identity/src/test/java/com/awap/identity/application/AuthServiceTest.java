package com.awap.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.identity.domain.RefreshToken;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.Tenant;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.RefreshTokenRepository;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.identity.infrastructure.security.JwtService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private TenantRepository tenantRepository;
  @Mock private UserRepository userRepository;
  @Mock private RefreshTokenRepository refreshTokenRepository;
  @Mock private JwtService jwtService;
  @Mock private PasswordEncoder passwordEncoder;

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthService(
            tenantRepository, userRepository, refreshTokenRepository, jwtService, passwordEncoder);
  }

  @Test
  void loginReturnsTokenPairForValidCredentials() {
    Tenant tenant = new Tenant("acme", "Acme", "FREE", 10, 5, 100);
    User user = new User(tenant.getId(), "admin@acme.com", "hash", "Admin", Role.TENANT_ADMIN);
    when(tenantRepository.findBySlugRls("acme")).thenReturn(Optional.of(tenant));
    when(userRepository.findByTenantIdAndEmailRls(tenant.getId(), "admin@acme.com"))
        .thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret123", "hash")).thenReturn(true);
    when(jwtService.generateAccessToken(
            user.getId(), tenant.getId(), user.getEmail(), user.getRole()))
        .thenReturn("access-token");
    when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
    when(jwtService.refreshTokenTtl()).thenReturn(Duration.ofDays(7));

    TokenPair pair = authService.login("acme", "admin@acme.com", "secret123");

    assertThat(pair.accessToken()).isEqualTo("access-token");
    assertThat(pair.refreshToken()).isNotBlank();
    assertThat(pair.expiresIn()).isEqualTo(900L);
    verify(refreshTokenRepository).save(any(RefreshToken.class));
  }

  @Test
  void loginRejectsWrongPassword() {
    Tenant tenant = new Tenant("acme", "Acme", "FREE", 10, 5, 100);
    User user = new User(tenant.getId(), "admin@acme.com", "hash", "Admin", Role.TENANT_ADMIN);
    when(tenantRepository.findBySlugRls("acme")).thenReturn(Optional.of(tenant));
    when(userRepository.findByTenantIdAndEmailRls(tenant.getId(), "admin@acme.com"))
        .thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

    assertThatThrownBy(() -> authService.login("acme", "admin@acme.com", "wrong"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void refreshRotatesValidToken() {
    Tenant tenant = new Tenant("acme", "Acme", "FREE", 10, 5, 100);
    User user = new User(tenant.getId(), "admin@acme.com", "hash", "Admin", Role.TENANT_ADMIN);
    RefreshToken stored =
        new RefreshToken(tenant.getId(), user.getId(), "hash", Instant.now().plusSeconds(60));
    when(refreshTokenRepository.findByTokenHashRls(any(String.class)))
        .thenReturn(Optional.of(stored));
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    when(jwtService.generateAccessToken(
            user.getId(), tenant.getId(), user.getEmail(), user.getRole()))
        .thenReturn("new-access");
    when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
    when(jwtService.refreshTokenTtl()).thenReturn(Duration.ofDays(7));

    TokenPair pair = authService.refresh("raw-refresh-token");

    assertThat(pair.accessToken()).isEqualTo("new-access");
    verify(refreshTokenRepository).revokeByTokenHash(any(String.class));
  }
}
