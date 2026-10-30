package com.awap.engine.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.SagaService;
import com.awap.engine.interfaces.dto.SagaResponse;
import com.awap.identity.infrastructure.security.TenantContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Saga inspection and manual intervention endpoints. */
@RestController
@RequestMapping("/api/v1/workflow-instances")
public class SagaController {

  private final SagaService sagaService;

  public SagaController(SagaService sagaService) {
    this.sagaService = sagaService;
  }

  @GetMapping("/{id}/saga")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<SagaResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(sagaService.get(currentTenantId(), id));
  }

  /**
   * Manual compensation. After an operator fixed the external system, this reopens dead-lettered
   * steps and resumes reverse-order compensation.
   */
  @PostMapping("/{id}/saga/compensate")
  @PreAuthorize("hasAuthority('INSTANCE_MANAGE')")
  public ResponseEntity<SagaResponse> compensate(@PathVariable UUID id) {
    return ResponseEntity.ok(sagaService.manualCompensate(currentTenantId(), id));
  }

  /** Manual retry of one dead-lettered compensation step, enforcing reverse order. */
  @PostMapping("/{id}/saga/steps/{stepId}/retry")
  @PreAuthorize("hasAuthority('INSTANCE_MANAGE')")
  public ResponseEntity<SagaResponse> retryStep(@PathVariable UUID id, @PathVariable UUID stepId) {
    return ResponseEntity.ok(sagaService.retryStep(currentTenantId(), id, stepId));
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
}
