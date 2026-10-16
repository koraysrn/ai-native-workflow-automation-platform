package com.awap.identity.infrastructure.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Extracts and validates the Bearer JWT, then populates the security and tenant contexts. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;

  public JwtAuthenticationFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String header = request.getHeader("Authorization");
      if (header != null && header.startsWith(BEARER_PREFIX)) {
        authenticate(header.substring(BEARER_PREFIX.length()));
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  private void authenticate(String token) {
    try {
      Claims claims = jwtService.parse(token);
      UUID userId = UUID.fromString(claims.getSubject());
      UUID tenantId = UUID.fromString(claims.get("tenantId", String.class));
      String role = claims.get("role", String.class);

      TenantContext.set(tenantId, userId, role);
      var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
      var authentication =
          new UsernamePasswordAuthenticationToken(userId.toString(), null, authorities);
      SecurityContextHolder.getContext().setAuthentication(authentication);
    } catch (RuntimeException ex) {
      SecurityContextHolder.clearContext();
    }
  }
}
