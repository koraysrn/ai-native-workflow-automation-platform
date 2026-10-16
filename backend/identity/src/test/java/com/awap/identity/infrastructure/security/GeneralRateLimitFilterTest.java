package com.awap.identity.infrastructure.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class GeneralRateLimitFilterTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private FilterChain filterChain;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private GeneralRateLimitFilter filter;

  @BeforeEach
  void setUp() {
    filter = new GeneralRateLimitFilter(redisTemplate);
  }

  @Test
  void overLimitReturns429() throws Exception {
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(301L);

    filter.doFilter(request, response, filterChain);

    verify(response).setStatus(429);
    verify(response).setHeader("Retry-After", "60");
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void underLimitContinuesChain() throws Exception {
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L);

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
  }
}
