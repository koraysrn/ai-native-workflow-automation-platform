package com.awap.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/** Guards the browser-facing CORS allow-list used by the live Next.js frontend. */
class SecurityConfigCorsTest {

  @Test
  void preflightAllowsTenantAndCsrfHeadersFromTheFrontendOrigin() {
    @SuppressWarnings("unchecked")
    ObjectProvider<GeneralRateLimitFilter> rateLimitFilters = mock(ObjectProvider.class);
    SecurityConfig config =
        new SecurityConfig(
            mock(JwtAuthenticationFilter.class),
            mock(AuthRateLimitFilter.class),
            mock(IdempotencyFilter.class),
            rateLimitFilters);

    CorsConfigurationSource source = config.corsConfigurationSource("http://localhost:3000");
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/tasks");
    request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");

    CorsConfiguration cors = source.getCorsConfiguration(request);

    assertThat(cors).isNotNull();
    assertThat(cors.getAllowedHeaders())
        .contains(
            "Authorization", "Content-Type", "Idempotency-Key", "X-Tenant-Id", "X-XSRF-TOKEN");
    assertThat(cors.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
  }

  @Test
  void csrfRequestHandlerAcceptsTheRawCookieTokenSentByTheSpa() {
    CsrfTokenRequestHandler handler = SecurityConfig.csrfTokenRequestHandler();
    CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token");
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
    request.addHeader("X-XSRF-TOKEN", "raw-token");

    assertThat(handler.resolveCsrfTokenValue(request, token)).isEqualTo("raw-token");
  }
}
