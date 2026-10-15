package com.awap.workflow.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionRequest;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionResponse;
import com.awap.workflow.interfaces.dto.WorkflowVersionResponse;
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

/** Workflow definition endpoints: draft, publish, rollback and versions. */
@RestController
@RequestMapping("/api/v1/workflow-definitions")
public class WorkflowDefinitionController {

  private final WorkflowDefinitionService service;

  public WorkflowDefinitionController(WorkflowDefinitionService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<WorkflowDefinitionResponse> create(
      @Valid @RequestBody WorkflowDefinitionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.create(currentTenantId(), request));
  }

  @GetMapping
  public ResponseEntity<List<WorkflowDefinitionResponse>> list() {
    return ResponseEntity.ok(service.list(currentTenantId()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<WorkflowDefinitionResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.get(currentTenantId(), id));
  }

  @PostMapping("/{id}/publish")
  public ResponseEntity<WorkflowDefinitionResponse> publish(@PathVariable UUID id) {
    return ResponseEntity.ok(service.publish(currentTenantId(), id));
  }

  @PostMapping("/{id}/rollback/{version}")
  public ResponseEntity<WorkflowDefinitionResponse> rollback(
      @PathVariable UUID id, @PathVariable int version) {
    return ResponseEntity.ok(service.rollback(currentTenantId(), id, version));
  }

  @GetMapping("/{id}/versions")
  public ResponseEntity<List<WorkflowVersionResponse>> versions(@PathVariable UUID id) {
    return ResponseEntity.ok(service.versions(currentTenantId(), id));
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
