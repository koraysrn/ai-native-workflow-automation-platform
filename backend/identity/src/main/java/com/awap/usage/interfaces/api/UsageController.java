package com.awap.usage.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.usage.application.UsageQueryService;
import com.awap.usage.domain.UsageMetric;
import com.awap.usage.interfaces.dto.UsageResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant usage reporting endpoint (issue #125). */
@RestController
@RequestMapping("/api/v1/tenants")
public class UsageController {

  private final UsageQueryService usageQueryService;

  public UsageController(UsageQueryService usageQueryService) {
    this.usageQueryService = usageQueryService;
  }

  @GetMapping("/{id}/usage")
  public ResponseEntity<UsageResponse> usage(
      @PathVariable UUID id,
      @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to,
      @RequestParam(name = "metric", required = false) String metricKey) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate end = to == null ? today : to;
    LocalDate start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "'from' must not be after 'to'.");
    }
    return ResponseEntity.ok(
        usageQueryService.report(
            id, currentTenantId(), currentRole(), start, end, parseMetric(metricKey)));
  }

  private Optional<UsageMetric> parseMetric(String metricKey) {
    if (metricKey == null || metricKey.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UsageMetric.fromKey(metricKey));
    } catch (IllegalArgumentException ex) {
      throw new AwapException(
          ErrorCode.USAGE_METRIC_UNKNOWN, HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  private UUID currentTenantId() {
    return TenantContext.current()
        .map(TenantContext.Context::tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."));
  }

  private String currentRole() {
    return TenantContext.current()
        .map(TenantContext.Context::role)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."));
  }
}
