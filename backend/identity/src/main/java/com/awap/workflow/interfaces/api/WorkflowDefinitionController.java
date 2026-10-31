package com.awap.workflow.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.workflow.application.ExpressionValidationResult;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.application.WorkflowExpressionValidationService;
import com.awap.workflow.interfaces.dto.ExpressionValidationRequest;
import com.awap.workflow.interfaces.dto.ExpressionValidationResponse;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionRequest;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionResponse;
import com.awap.workflow.interfaces.dto.WorkflowVersionResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workflow definition endpoints: draft, publish, rollback, versions and expression dry-runs. */
@RestController
@RequestMapping("/api/v1/workflow-definitions")
public class WorkflowDefinitionController {

  private final WorkflowDefinitionService service;
  private final WorkflowExpressionValidationService expressionValidationService;

  public WorkflowDefinitionController(
      WorkflowDefinitionService service,
      WorkflowExpressionValidationService expressionValidationService) {
    this.service = service;
    this.expressionValidationService = expressionValidationService;
  }

  @PostMapping
  @PreAuthorize("hasAuthority('WORKFLOW_CREATE')")
  public ResponseEntity<WorkflowDefinitionResponse> create(
      @Valid @RequestBody WorkflowDefinitionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.create(currentTenantId(), request));
  }

  /**
   * Validates an edge or node expression without persisting anything. A blank expression is valid
   * because it means the transition is unconditional; every other failure is returned as a
   * field-level error so the designer can render it inline and block saving.
   */
  @PostMapping("/validate-expression")
  @PreAuthorize("hasAuthority('WORKFLOW_CREATE')")
  public ResponseEntity<ExpressionValidationResponse> validateExpression(
      @Valid @RequestBody ExpressionValidationRequest request) {
    currentTenantId();
    ExpressionValidationResult result = expressionValidationService.validate(request.expression());
    return ResponseEntity.ok(toResponse(result));
  }

  @GetMapping
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<WorkflowDefinitionResponse>> list() {
    return ResponseEntity.ok(service.list(currentTenantId()));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<WorkflowDefinitionResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.get(currentTenantId(), id));
  }

  @PostMapping("/{id}/publish")
  @PreAuthorize("hasAuthority('WORKFLOW_PUBLISH')")
  public ResponseEntity<WorkflowDefinitionResponse> publish(@PathVariable UUID id) {
    return ResponseEntity.ok(service.publish(currentTenantId(), id));
  }

  @PostMapping("/{id}/rollback/{version}")
  @PreAuthorize("hasAuthority('WORKFLOW_PUBLISH')")
  public ResponseEntity<WorkflowDefinitionResponse> rollback(
      @PathVariable UUID id, @PathVariable int version) {
    return ResponseEntity.ok(service.rollback(currentTenantId(), id, version));
  }

  @GetMapping("/{id}/versions")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<WorkflowVersionResponse>> versions(@PathVariable UUID id) {
    return ResponseEntity.ok(service.versions(currentTenantId(), id));
  }

  @PostMapping("/{id}/versions/{version}/archive")
  @PreAuthorize("hasAuthority('WORKFLOW_PUBLISH')")
  public ResponseEntity<Void> archiveVersion(@PathVariable UUID id, @PathVariable int version) {
    service.archiveVersion(currentTenantId(), id, version);
    return ResponseEntity.noContent().build();
  }

  private ExpressionValidationResponse toResponse(ExpressionValidationResult result) {
    List<ExpressionValidationResponse.FieldError> errors =
        result.errors().stream()
            .map(
                error ->
                    new ExpressionValidationResponse.FieldError(error.field(), error.message()))
            .toList();
    return new ExpressionValidationResponse(result.valid(), errors);
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
