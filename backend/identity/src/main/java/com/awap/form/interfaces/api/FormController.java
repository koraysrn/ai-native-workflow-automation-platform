package com.awap.form.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.form.application.FormService;
import com.awap.form.interfaces.dto.FormDefinitionRequest;
import com.awap.form.interfaces.dto.FormDefinitionResponse;
import com.awap.form.interfaces.dto.FormSubmissionRequest;
import com.awap.form.interfaces.dto.FormSubmissionResponse;
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

/** Form definition and submission endpoints. */
@RestController
@RequestMapping("/api/v1/forms")
public class FormController {

  private final FormService formService;

  public FormController(FormService formService) {
    this.formService = formService;
  }

  @PostMapping
  public ResponseEntity<FormDefinitionResponse> create(
      @Valid @RequestBody FormDefinitionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(formService.create(currentTenantId(), request));
  }

  @GetMapping
  public ResponseEntity<List<FormDefinitionResponse>> list() {
    return ResponseEntity.ok(formService.list(currentTenantId()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<FormDefinitionResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(formService.get(currentTenantId(), id));
  }

  @PostMapping("/{id}/submissions")
  public ResponseEntity<FormSubmissionResponse> submit(
      @PathVariable UUID id, @Valid @RequestBody FormSubmissionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(formService.submit(currentTenantId(), id, request, currentUserId()));
  }

  @GetMapping("/{id}/submissions")
  public ResponseEntity<List<FormSubmissionResponse>> submissions(@PathVariable UUID id) {
    return ResponseEntity.ok(formService.submissions(currentTenantId(), id));
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

  private UUID currentUserId() {
    return TenantContext.current()
        .map(TenantContext.Context::userId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."));
  }
}
