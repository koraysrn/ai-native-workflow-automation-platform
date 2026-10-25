package com.awap.identity.infrastructure.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits auth endpoints (login/register/refresh) per client IP: 10 requests per 60s window.
 *
 * <p>When {@code awap.auth-rate-limit.redis} is enabled the counter lives in Redis behind an atomic
 * {@code INCR + EXPIRE} Lua script, so brute-force protection is correct across multiple backend
 * replicas. When Redis is unreachable the filter fails <b>closed</b> (503) for auth endpoints
 * rather than silently allowing brute-force attempts; this trade-off is documented in {@code
 * docs/operations-runbook.md}. When Redis is disabled it falls back to a per-instance Caffeine
 * counter for local development.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

  static final int LIMIT = 10;
  static final long WINDOW_SECONDS = 60;
  static final String KEY_PREFIX = "rl:auth:";

  private static final String SCRIPT =
      "local current = redis.call('INCR', KEYS[1]) "
          + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end "
          + "return current";

  private final boolean redisEnabled;
  private final StringRedisTemplate redisTemplate;
  private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(SCRIPT, Long.class);
  private final Cache<String, AtomicInteger> local =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofSeconds(WINDOW_SECONDS))
          .maximumSize(10_000)
          .build();

  public AuthRateLimitFilter(
      @Value("${awap.auth-rate-limit.redis:false}") boolean redisEnabled,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
    this.redisEnabled = redisEnabled;
    this.redisTemplate = redisTemplateProvider.getIfAvailable();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(HttpMethod.POST.matches(request.getMethod())
        && (path.endsWith("/auth/login")
            || path.endsWith("/auth/register")
            || path.endsWith("/auth/refresh")));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String ip = clientIp(request);

    if (redisEnabled) {
      if (redisTemplate == null) {
        failClosed(response);
        return;
      }
      try {
        Long count =
            redisTemplate.execute(
                script,
                List.of(KEY_PREFIX + ip),
                String.valueOf(LIMIT),
                String.valueOf(WINDOW_SECONDS));
        if (count != null && count > LIMIT) {
          reject(response);
          return;
        }
      } catch (Exception ex) {
        failClosed(response);
        return;
      }
    } else {
      AtomicInteger counter = local.get(ip, key -> new AtomicInteger());
      if (counter.incrementAndGet() > LIMIT) {
        reject(response);
        return;
      }
    }

    filterChain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response) throws IOException {
    response.setStatus(429);
    response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Rate limit exceeded. Retry later.\"}");
  }

  private void failClosed(HttpServletResponse response) throws IOException {
    response.setStatus(503);
    response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,"
                + "\"detail\":\"Authentication is temporarily unavailable.\"}");
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
