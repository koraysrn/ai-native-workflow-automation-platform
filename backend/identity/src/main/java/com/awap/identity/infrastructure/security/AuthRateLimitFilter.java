package com.awap.identity.infrastructure.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rate limits auth endpoints per client IP (10 requests per minute). */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

  private static final int LIMIT = 10;

  private final Cache<String, AtomicInteger> attempts =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(HttpMethod.POST.matches(request.getMethod())
        && (path.endsWith("/auth/login") || path.endsWith("/auth/refresh")));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String ip = clientIp(request);
    AtomicInteger counter = attempts.get(ip, key -> new AtomicInteger());
    if (counter.incrementAndGet() > LIMIT) {
      response.setStatus(429);
      response.setHeader("Retry-After", "60");
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write(
              "{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Rate limit exceeded. Retry later.\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
