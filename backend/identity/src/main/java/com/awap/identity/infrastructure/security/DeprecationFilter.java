package com.awap.identity.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Injects `Deprecation` and `Sunset` headers for deprecated API path prefixes. */
@Component
public class DeprecationFilter extends OncePerRequestFilter {

  private final Map<String, String> deprecatedPaths;

  public DeprecationFilter(@Value("${awap.api.deprecated-paths:}") String spec) {
    this.deprecatedPaths = parse(spec);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    deprecatedPaths.forEach(
        (prefix, sunset) -> {
          if (path.startsWith(prefix)) {
            response.setHeader("Deprecation", "true");
            response.setHeader("Sunset", sunset);
          }
        });
    filterChain.doFilter(request, response);
  }

  /** Parses {@code /api/v1/old=2027-01-01T00:00:00Z,/api/v1/older=2027-06-01T00:00:00Z}. */
  private static Map<String, String> parse(String spec) {
    Map<String, String> result = new LinkedHashMap<>();
    if (spec == null || spec.isBlank()) {
      return result;
    }
    for (String entry : spec.split(",")) {
      String[] parts = entry.split("=", 2);
      if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
        result.put(parts[0].trim(), parts[1].trim());
      }
    }
    return result;
  }
}
