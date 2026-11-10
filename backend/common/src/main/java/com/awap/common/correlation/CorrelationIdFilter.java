package com.awap.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts {@code X-Correlation-Id} or generates one, publishes it in the MDC for the whole request
 * and echoes it on the response (issue #191).
 *
 * <p>The filter runs before every other servlet filter (security, logging) so all log lines of a
 * request carry the ID. The MDC entry is always removed in a {@code finally} block; a pooled
 * container thread must never leak one request's correlation ID into the next request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String candidate = request.getHeader(CorrelationId.HEADER);
    String correlationId = CorrelationId.resolve(candidate);
    if (candidate != null && !candidate.equals(correlationId)) {
      // Never log the raw rejected value: it may contain control characters or PII.
      LOGGER.warn(
          "Rejected an unsafe {} header; generated a new correlation ID", CorrelationId.HEADER);
    }
    CorrelationId.set(correlationId);
    response.setHeader(CorrelationId.HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      CorrelationId.clear();
    }
  }
}
