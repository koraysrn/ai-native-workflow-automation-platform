package com.awap.identity.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** General API rate limit (300 requests/min per client IP) backed by Redis. */
@Component
@ConditionalOnProperty(name = "awap.rate-limit.enabled", havingValue = "true")
public class GeneralRateLimitFilter extends OncePerRequestFilter {

  private static final long LIMIT = 300;
  private static final long WINDOW_SECONDS = 60;
  private static final String SCRIPT =
      "local current = redis.call('INCR', KEYS[1]) "
          + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end "
          + "return current";

  private final StringRedisTemplate redisTemplate;
  private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(SCRIPT, Long.class);

  public GeneralRateLimitFilter(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String key = "rl:" + clientIp(request);
    Long count =
        redisTemplate.execute(
            script, List.of(key), String.valueOf(LIMIT), String.valueOf(WINDOW_SECONDS));
    if (count != null && count > LIMIT) {
      response.setStatus(429);
      response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write(
              "{\"title\":\"Too Many Requests\",\"status\":429,"
                  + "\"detail\":\"Rate limit exceeded. Retry later.\"}");
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
