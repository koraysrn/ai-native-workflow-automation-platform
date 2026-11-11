package com.awap.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRateLimitFilterTest {

  private ObjectProvider<StringRedisTemplate> providerWith(StringRedisTemplate template) {
    return new ObjectProvider<>() {
      @Override
      public StringRedisTemplate getObject(Object... args) {
        return template;
      }

      @Override
      public StringRedisTemplate getIfAvailable() {
        return template;
      }

      @Override
      public StringRedisTemplate getIfUnique() {
        return template;
      }

      @Override
      public StringRedisTemplate getObject() {
        return template;
      }
    };
  }

  private MockHttpServletRequest authRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setRemoteAddr("203.0.113.7");
    return request;
  }

  @Test
  void redisPathRejectsEleventhRequestWith429AndRetryAfter() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(11L);

    AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 10, true, providerWith(redis));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(authRequest(), response, mock(FilterChain.class));

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    assertThat(response.getContentAsString()).contains("Too Many Requests");
  }

  @Test
  void redisPathAllowsWithinLimit() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(10L);

    AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 10, true, providerWith(redis));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(authRequest(), response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void redisFailureFailsClosedWith503() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(), anyList(), any(Object[].class)))
        .thenThrow(new RuntimeException("redis down"));

    AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 10, true, providerWith(redis));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(authRequest(), response, mock(FilterChain.class));

    assertThat(response.getStatus()).isEqualTo(503);
  }

  @Test
  void localFallbackRejectsEleventhRequest() throws Exception {
    AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 10, false, providerWith(null));

    MockHttpServletResponse rejected = null;
    for (int i = 0; i < 11; i++) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(authRequest(), response, mock(FilterChain.class));
      rejected = response;
    }

    assertThat(rejected.getStatus()).isEqualTo(429);
  }

  @Test
  void independentBucketsPerIpInLocalFallback() throws Exception {
    AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 10, false, providerWith(null));

    for (int i = 0; i < 10; i++) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(authRequest(), response, mock(FilterChain.class));
    }

    MockHttpServletRequest other = authRequest();
    other.setRemoteAddr("198.51.100.9");
    MockHttpServletResponse otherResponse = new MockHttpServletResponse();
    filter.doFilter(other, otherResponse, mock(FilterChain.class));

    assertThat(otherResponse.getStatus()).isEqualTo(200);
  }

  @Test
  void blankForwardedForFallsBackToRemoteAddress() throws Exception {
    AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 10, false, providerWith(null));
    MockHttpServletRequest request = authRequest();
    request.addHeader("X-Forwarded-For", "   ");

    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, mock(FilterChain.class));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void disabledFilterSkipsEveryAuthEndpoint() throws Exception {
    AuthRateLimitFilter filter = new AuthRateLimitFilter(false, 1, false, providerWith(null));
    FilterChain chain = mock(FilterChain.class);

    for (int i = 0; i < 25; i++) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(authRequest(), response, chain);
      assertThat(response.getStatus()).isEqualTo(200);
    }
    // A non-auth path is unaffected either way.
    assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/tasks"))).isTrue();
  }

  @Test
  void configuredLimitAppliesToBothBackends() throws Exception {
    AuthRateLimitFilter local = new AuthRateLimitFilter(true, 2, false, providerWith(null));
    MockHttpServletResponse limited = null;
    for (int i = 0; i < 3; i++) {
      limited = new MockHttpServletResponse();
      local.doFilter(authRequest(), limited, mock(FilterChain.class));
    }
    assertThat(limited.getStatus()).isEqualTo(429);

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(3L);
    AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 2, true, providerWith(redis));
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(authRequest(), response, mock(FilterChain.class));
    assertThat(response.getStatus()).isEqualTo(429);
  }
}
