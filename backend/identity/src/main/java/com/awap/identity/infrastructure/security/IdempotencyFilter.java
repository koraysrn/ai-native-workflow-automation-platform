package com.awap.identity.infrastructure.security;

import com.awap.identity.domain.IdempotencyRecord;
import com.awap.identity.infrastructure.IdempotencyRecordRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Replays a previous response for a duplicate Idempotency-Key; otherwise caches the response. */
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

    Optional<IdempotencyRecord> existing =
        idempotencyRecordRepository.findByTenantIdAndIdempotencyKey(tenantId, key);
    if (existing.isPresent()) {
      replay(existing.get(), response);
      return;
    }

    IdempotencyRecord record = new IdempotencyRecord(tenantId, key);
    try {
      idempotencyRecordRepository.save(record);
    } catch (DataIntegrityViolationException ex) {
      Optional<IdempotencyRecord> raced =
          idempotencyRecordRepository.findByTenantIdAndIdempotencyKey(tenantId, key);
      if (raced.isPresent()) {
        replay(raced.get(), response);
      } else {
        writeConflict(response);
      }
      return;
    }

    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    try {
      filterChain.doFilter(request, wrapper);
    } finally {
      byte[] body = wrapper.getContentAsByteArray();
      record.setResponse(
          wrapper.getStatus(), new String(body, StandardCharsets.UTF_8), wrapper.getContentType());
      idempotencyRecordRepository.save(record);
      wrapper.copyBodyToResponse();
    }
  }

  private void replay(IdempotencyRecord record, HttpServletResponse response) throws IOException {
    if (record.getResponseStatus() == 0) {
      writeConflict(response);
      return;
    }
    response.setStatus(record.getResponseStatus());
    if (record.getResponseContentType() != null) {
      response.setContentType(record.getResponseContentType());
    }
    if (record.getResponseBody() != null) {
      response.getWriter().write(record.getResponseBody());
    }
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
