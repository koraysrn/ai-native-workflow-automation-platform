package com.awap.identity.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.identity.application.AuthService;
import com.awap.identity.application.TokenPair;
import com.awap.identity.interfaces.dto.LoginRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

/** Verifies that auth cookies always carry HttpOnly, Secure and SameSite flags. */
class AuthControllerTest {

  @Test
  void loginWritesCookiesWithSecurityFlags() {
    AuthService authService = mock(AuthService.class);
    when(authService.login("acme", "user@example.com", "pw"))
        .thenReturn(new TokenPair("access", "refresh", 900L));

    AuthController controller = new AuthController(authService, true, "Strict");
    HttpServletResponse response = mock(HttpServletResponse.class);

    controller.login(new LoginRequest("acme", "user@example.com", "pw"), response);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(response, times(3)).addHeader(eq(HttpHeaders.SET_COOKIE), captor.capture());
    List<String> cookies = captor.getAllValues();

    assertThat(cookies).hasSize(3);
    assertThat(cookies.get(0))
        .contains("awap_access_token")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Strict");
    assertThat(cookies.get(1))
        .contains("awap_refresh_token")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Strict");
    assertThat(cookies.get(2)).contains("awap_session").contains("SameSite=Strict");
  }

  @Test
  void logoutClearsCookies() {
    AuthService authService = mock(AuthService.class);
    AuthController controller = new AuthController(authService, true, "Strict");
    HttpServletResponse response = mock(HttpServletResponse.class);

    controller.logout(null, "refresh-token", response);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(response, times(3)).addHeader(eq(HttpHeaders.SET_COOKIE), captor.capture());
    assertThat(captor.getAllValues())
        .allMatch(cookie -> cookie.contains("Max-Age=0") && cookie.contains("HttpOnly"));
  }
}
