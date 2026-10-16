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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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
  private final boolean cookieSecure;

  public AuthController(
      AuthService authService,
      @Value("${awap.security.cookie-secure:false}") boolean cookieSecure) {
    this.authService = authService;
    this.cookieSecure = cookieSecure;
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    TokenPair pair = authService.login(request.tenantSlug(), request.email(), request.password());
    writeTokenCookies(response, pair);
    return ResponseEntity.ok(new TokenResponse(null, null, pair.expiresIn()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      @RequestBody(required = false) @Valid RefreshRequest request,
      @CookieValue(name = "awap_refresh_token", required = false) String refreshToken,
      HttpServletResponse response) {
    String token =
        refreshToken != null ? refreshToken : (request != null ? request.refreshToken() : null);
    if (token == null) {
      throw new AwapException(
          ErrorCode.REFRESH_TOKEN_INVALID,
          HttpStatus.UNAUTHORIZED,
          "Refresh token is invalid or expired.");
    }
    TokenPair pair = authService.refresh(token);
    writeTokenCookies(response, pair);
    return ResponseEntity.ok(new TokenResponse(null, null, pair.expiresIn()));
  }

  @PostMapping("/logout")
  public ResponseEntity<MessageResponse> logout(
      @RequestBody(required = false) @Valid RefreshRequest request,
      @CookieValue(name = "awap_refresh_token", required = false) String refreshToken,
      HttpServletResponse response) {
    String token =
        refreshToken != null ? refreshToken : (request != null ? request.refreshToken() : null);
    authService.logout(token);
    clearTokenCookies(response);
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

  private void writeTokenCookies(HttpServletResponse response, TokenPair pair) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("awap_access_token", pair.accessToken())
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path("/")
            .maxAge(pair.expiresIn())
            .build()
            .toString());
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("awap_refresh_token", pair.refreshToken())
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path("/api/v1/auth")
            .maxAge(Duration.ofDays(7))
            .build()
            .toString());
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("awap_session", "1")
            .httpOnly(false)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ofDays(7))
            .build()
            .toString());
  }

  private void clearTokenCookies(HttpServletResponse response) {
    for (String name : new String[] {"awap_access_token", "awap_refresh_token", "awap_session"}) {
      response.addHeader(
          HttpHeaders.SET_COOKIE,
          ResponseCookie.from(name, "")
              .httpOnly(true)
              .secure(cookieSecure)
              .sameSite("Lax")
              .path("/")
              .maxAge(0)
              .build()
              .toString());
    }
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
