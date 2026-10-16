package com.awap.identity.infrastructure.security;

import com.awap.identity.domain.Role;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
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
      String token = resolveToken(request);
      if (token != null) {
        authenticate(token);
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if ("awap_access_token".equals(cookie.getName())) {
          return cookie.getValue();
        }
      }
    }
    return null;
  }

  private void authenticate(String token) {
    try {
      Claims claims = jwtService.parse(token);
      UUID userId = UUID.fromString(claims.getSubject());
      UUID tenantId = UUID.fromString(claims.get("tenantId", String.class));
      String role = claims.get("role", String.class);

      TenantContext.set(tenantId, userId, role);
      List<SimpleGrantedAuthority> authorities = new ArrayList<>();
      authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
      try {
        Role parsedRole = Role.valueOf(role);
        parsedRole
            .permissions()
            .forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
      } catch (IllegalArgumentException ex) {
        // unknown role: keep only the ROLE_ authority
      }
      var authentication =
          new UsernamePasswordAuthenticationToken(userId.toString(), null, authorities);
      SecurityContextHolder.getContext().setAuthentication(authentication);
    } catch (RuntimeException ex) {
      SecurityContextHolder.clearContext();
    }
  }
}
