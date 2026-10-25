package com.awap.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Negative/authz paths: account lockout, refresh revocation/expiry and logout edge cases. */
@ExtendWith(MockitoExtension.class)
class AuthServiceNegativeTest {

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

  private Tenant tenant() {
    return new Tenant("acme", "Acme", "FREE", 10, 5, 100);
  }

  @Test
  void loginLockedAccountReturnsLockedStatus() {
    Tenant tenant = tenant();
    User user = new User(tenant.getId(), "locked@acme.com", "hash", "Locked", Role.EMPLOYEE);
    for (int i = 0; i < 5; i++) {
      user.registerFailedLogin();
    }
    when(tenantRepository.findBySlugRls("acme")).thenReturn(Optional.of(tenant));
    when(userRepository.findByTenantIdAndEmailRls(tenant.getId(), "locked@acme.com"))
        .thenReturn(Optional.of(user));

    assertThatThrownBy(() -> authService.login("acme", "locked@acme.com", "whatever"))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.LOCKED));
    verify(passwordEncoder, never()).matches(anyString(), anyString());
  }

  @Test
  void loginWrongPasswordRecordsFailedAttempt() {
    Tenant tenant = tenant();
    User user = new User(tenant.getId(), "u@acme.com", "hash", "U", Role.EMPLOYEE);
    when(tenantRepository.findBySlugRls("acme")).thenReturn(Optional.of(tenant));
    when(userRepository.findByTenantIdAndEmailRls(tenant.getId(), "u@acme.com"))
        .thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

    assertThatThrownBy(() -> authService.login("acme", "u@acme.com", "wrong"))
        .isInstanceOf(AwapException.class);

    verify(userRepository).recordFailedLogin(user.getId());
  }

  @Test
  void refreshRevokedTokenThrows() {
    Tenant tenant = tenant();
    User user = new User(tenant.getId(), "u@acme.com", "hash", "U", Role.EMPLOYEE);
    RefreshToken revoked =
        new RefreshToken(tenant.getId(), user.getId(), "hash", Instant.now().plusSeconds(60));
    revoked.revoke();
    when(refreshTokenRepository.findByTokenHashRls(any(String.class)))
        .thenReturn(Optional.of(revoked));

    assertThatThrownBy(() -> authService.refresh("raw-token")).isInstanceOf(AwapException.class);
  }

  @Test
  void refreshExpiredTokenThrows() {
    Tenant tenant = tenant();
    User user = new User(tenant.getId(), "u@acme.com", "hash", "U", Role.EMPLOYEE);
    RefreshToken expired =
        new RefreshToken(tenant.getId(), user.getId(), "hash", Instant.now().minusSeconds(1));
    when(refreshTokenRepository.findByTokenHashRls(any(String.class)))
        .thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> authService.refresh("raw-token")).isInstanceOf(AwapException.class);
  }

  @Test
  void refreshUnknownTokenThrows() {
    when(refreshTokenRepository.findByTokenHashRls(any(String.class))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authService.refresh("raw-token")).isInstanceOf(AwapException.class);
  }

  @Test
  void logoutBlankTokenIsNoop() {
    authService.logout(null);
    authService.logout("   ");

    verifyNoInteractions(refreshTokenRepository);
  }

  @Test
  void logoutRevokesTokenByHash() {
    authService.logout("raw-token");

    verify(refreshTokenRepository).revokeByTokenHash(any(String.class));
  }

  @Test
  void meUnknownUserThrowsUnauthorized() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authService.me(userId))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }
}
