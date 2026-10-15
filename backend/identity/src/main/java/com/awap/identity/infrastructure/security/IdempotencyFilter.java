package com.awap.identity.infrastructure.security;

import com.awap.identity.domain.IdempotencyRecord;
import com.awap.identity.infrastructure.IdempotencyRecordRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects duplicate mutating requests carrying an already-used Idempotency-Key header. */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final IdempotencyRecordRepository idempotencyRecordRepository;

  public IdempotencyFilter(IdempotencyRecordRepository idempotencyRecordRepository) {
    this.idempotencyRecordRepository = idempotencyRecordRepository;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String key = request.getHeader("Idempotency-Key");
    return !MUTATING_METHODS.contains(request.getMethod())
        || key == null
        || key.isBlank()
        || TenantContext.current().isEmpty();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String key = request.getHeader("Idempotency-Key");
    UUID tenantId = TenantContext.current().get().tenantId();

    if (idempotencyRecordRepository.existsByTenantIdAndIdempotencyKey(tenantId, key)) {
      writeConflict(response);
      return;
    }

    try {
      idempotencyRecordRepository.save(new IdempotencyRecord(tenantId, key));
    } catch (DataIntegrityViolationException ex) {
      writeConflict(response);
      return;
    }

    filterChain.doFilter(request, response);
  }

  private void writeConflict(HttpServletResponse response) throws IOException {
    response.setStatus(409);
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            "{\"title\":\"Conflict\",\"status\":409,\"detail\":\"Idempotency-Key has already been used.\"}");
  }
}
