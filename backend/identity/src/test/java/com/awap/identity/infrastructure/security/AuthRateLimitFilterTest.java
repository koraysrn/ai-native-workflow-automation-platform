package com.awap.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies the auth-endpoint rate limiter returns 429 after the per-IP threshold. */
class AuthRateLimitFilterTest {

  @Test
  void blocksAfterTenLoginAttemptsFromSameIp() throws Exception {
    AuthRateLimitFilter filter = new AuthRateLimitFilter();

    for (int i = 0; i < 10; i++) {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
      request.setRemoteAddr("192.0.2.1");
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, new MockFilterChain());
      assertThat(response.getStatus()).isEqualTo(200);
    }

    MockHttpServletRequest eleventh = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    eleventh.setRemoteAddr("192.0.2.1");
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(eleventh, blocked, new MockFilterChain());

    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
  }

  @Test
  void skipsNonAuthEndpoints() throws Exception {
    AuthRateLimitFilter filter = new AuthRateLimitFilter();

    for (int i = 0; i < 15; i++) {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
      request.setRemoteAddr("192.0.2.2");
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, new MockFilterChain());
      assertThat(response.getStatus()).isEqualTo(200);
    }
  }
}
