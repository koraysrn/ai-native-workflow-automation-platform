package com.awap.identity.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.application.AuthService;
import com.awap.identity.application.TokenPair;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.identity.interfaces.dto.LoginRequest;
import com.awap.identity.interfaces.dto.MessageResponse;
import com.awap.identity.interfaces.dto.RefreshRequest;
import com.awap.identity.interfaces.dto.TokenResponse;
import com.awap.identity.interfaces.dto.UserResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentication endpoints: login, logout, refresh and current user. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    TokenPair pair = authService.login(request.tenantSlug(), request.email(), request.password());
    return ResponseEntity.ok(toTokenResponse(pair));
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
    TokenPair pair = authService.refresh(request.refreshToken());
    return ResponseEntity.ok(toTokenResponse(pair));
  }

  @PostMapping("/logout")
  public ResponseEntity<MessageResponse> logout(@Valid @RequestBody RefreshRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.ok(new MessageResponse("Logged out."));
  }

  @GetMapping("/me")
  public ResponseEntity<UserResponse> me() {
    UUID userId = currentUserId();
    User user = authService.me(userId);
    return ResponseEntity.ok(
        new UserResponse(
            user.getId(),
            user.getTenantId(),
            user.getEmail(),
            user.getFullName(),
            user.getRole().name()));
  }

  private TokenResponse toTokenResponse(TokenPair pair) {
    return new TokenResponse(pair.accessToken(), pair.refreshToken(), pair.expiresIn());
  }

  private UUID currentUserId() {
    return TenantContext.current()
        .map(TenantContext.Context::userId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."));
  }
}
