package com.awap.engine.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.WorkflowEngineService;
import com.awap.engine.interfaces.dto.ExecutionLogResponse;
import com.awap.engine.interfaces.dto.StartWorkflowRequest;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import com.awap.identity.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workflow instance endpoints: start, list, get, logs and state transitions. */
@RestController
@RequestMapping("/api/v1/workflow-instances")
public class WorkflowInstanceController {

  private final WorkflowEngineService engineService;

  public WorkflowInstanceController(WorkflowEngineService engineService) {
    this.engineService = engineService;
  }

  @PostMapping
  public ResponseEntity<WorkflowInstanceResponse> start(
      @Valid @RequestBody StartWorkflowRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(engineService.start(currentTenantId(), request));
  }

  @GetMapping
  public ResponseEntity<List<WorkflowInstanceResponse>> list() {
    return ResponseEntity.ok(engineService.list(currentTenantId()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<WorkflowInstanceResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(engineService.get(currentTenantId(), id));
  }

  @GetMapping("/{id}/logs")
  public ResponseEntity<List<ExecutionLogResponse>> logs(@PathVariable UUID id) {
    return ResponseEntity.ok(engineService.logs(currentTenantId(), id));
  }

  @PostMapping("/{id}/complete")
  public ResponseEntity<WorkflowInstanceResponse> complete(@PathVariable UUID id) {
    return ResponseEntity.ok(engineService.complete(currentTenantId(), id));
  }

  @PostMapping("/{id}/fail")
  public ResponseEntity<WorkflowInstanceResponse> fail(@PathVariable UUID id) {
    return ResponseEntity.ok(engineService.fail(currentTenantId(), id));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<WorkflowInstanceResponse> cancel(@PathVariable UUID id) {
    return ResponseEntity.ok(engineService.cancel(currentTenantId(), id));
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
