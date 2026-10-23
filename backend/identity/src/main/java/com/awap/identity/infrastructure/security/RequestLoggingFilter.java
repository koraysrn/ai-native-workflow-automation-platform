package com.awap.identity.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs every HTTP request: method, path, response status, duration and the tenant/trace
 * identifiers. The trace and correlation IDs are also emitted by the structured JSON log appender.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.currentTimeMillis();
    try {
      chain.doFilter(request, response);
    } finally {
      long duration = System.currentTimeMillis() - start;
      log.info(
          "HTTP {} {} -> status={} durationMs={} tenant={} traceId={}",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          duration,
          request.getHeader("X-Tenant-Id"),
          MDC.get("traceId"));
    }
  }
}
