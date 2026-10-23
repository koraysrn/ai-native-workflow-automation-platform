package com.awap.identity.infrastructure.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

/** Verifies the Deprecation/Sunset header injection for deprecated API paths. */
class DeprecationFilterTest {

  @Test
  void addsDeprecationHeadersForMatchingPath() throws Exception {
    DeprecationFilter filter = new DeprecationFilter("/api/v1/old=2027-01-01T00:00:00Z");
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getRequestURI()).thenReturn("/api/v1/old/things");

    filter.doFilter(request, response, chain);

    verify(response).setHeader("Deprecation", "true");
    verify(response).setHeader("Sunset", "2027-01-01T00:00:00Z");
    verify(chain).doFilter(request, response);
  }

  @Test
  void addsNoHeadersForNonDeprecatedPath() throws Exception {
    DeprecationFilter filter = new DeprecationFilter("/api/v1/old=2027-01-01T00:00:00Z");
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getRequestURI()).thenReturn("/api/v1/current/things");

    filter.doFilter(request, response, chain);

    verify(response, never()).setHeader("Deprecation", "true");
    verify(chain).doFilter(request, response);
  }
}
