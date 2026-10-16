package com.awap.identity.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.application.TenantService;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.identity.interfaces.dto.LimitsRequest;
import com.awap.identity.interfaces.dto.TenantRequest;
import com.awap.identity.interfaces.dto.TenantResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant on-boarding and quota management endpoints. */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

  private final TenantService tenantService;

  public TenantController(TenantService tenantService) {
    this.tenantService = tenantService;
  }

  @PostMapping
  public ResponseEntity<TenantResponse> create(@Valid @RequestBody TenantRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(tenantService.createTenant(request));
  }

  @GetMapping("/{id}")
  public ResponseEntity<TenantResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(tenantService.getTenant(id, currentTenantId(), currentRole()));
  }

  @PutMapping("/{id}/limits")
  public ResponseEntity<TenantResponse> updateLimits(
      @PathVariable UUID id, @Valid @RequestBody LimitsRequest request) {
    return ResponseEntity.ok(
        tenantService.updateLimits(id, request, currentTenantId(), currentRole()));
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
