package com.awap.notification.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.notification.application.NotificationTemplateService;
import com.awap.notification.domain.NotificationTemplate;
import com.awap.notification.domain.NotificationTemplateVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped notification template management, revision history and preview. */
@RestController
@RequestMapping("/api/v1/notification-templates")
public class NotificationTemplateController {

  private final NotificationTemplateService templateService;

  public NotificationTemplateController(NotificationTemplateService templateService) {
    this.templateService = templateService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<NotificationTemplateResponse>> list(
      @RequestParam(name = "templateKey", required = false) String templateKey) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(
        templateService.list(tenantId, templateKey).stream().map(this::toResponse).toList());
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<NotificationTemplateResponse> get(@PathVariable("id") String id) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(toResponse(templateService.get(tenantId, templateId(id))));
  }

  @GetMapping("/{id}/versions")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<NotificationTemplateVersionResponse>> versions(
      @PathVariable("id") String id) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(
        templateService.versions(tenantId, templateId(id)).stream()
            .map(this::toVersionResponse)
            .toList());
  }

  @PostMapping
  @PreAuthorize("hasAuthority('INSTANCE_MANAGE')")
  public ResponseEntity<NotificationTemplateResponse> create(
      @Valid @RequestBody CreateNotificationTemplateRequest request) {
    UUID tenantId = requireTenantId();
    NotificationTemplate created =
        templateService.create(
            tenantId,
            request.templateKey(),
            request.locale(),
            request.subject(),
            request.body(),
            actorId());
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('INSTANCE_MANAGE')")
  public ResponseEntity<NotificationTemplateResponse> update(
      @PathVariable("id") String id,
      @Valid @RequestBody UpdateNotificationTemplateRequest request) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(
        toResponse(
            templateService.update(
                tenantId,
                templateId(id),
                request.expectedVersion(),
                request.subject(),
                request.body(),
                actorId())));
  }

  @PostMapping("/{id}/archive")
  @PreAuthorize("hasAuthority('INSTANCE_MANAGE')")
  public ResponseEntity<NotificationTemplateResponse> archive(@PathVariable("id") String id) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(
        toResponse(templateService.archive(tenantId, templateId(id), actorId())));
  }

  @PostMapping("/{id}/activate")
  @PreAuthorize("hasAuthority('INSTANCE_MANAGE')")
  public ResponseEntity<NotificationTemplateResponse> activate(@PathVariable("id") String id) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(
        toResponse(templateService.activate(tenantId, templateId(id), actorId())));
  }

  @PostMapping("/preview")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<NotificationTemplatePreviewResponse> preview(
      @Valid @RequestBody PreviewNotificationTemplateRequest request) {
    UUID tenantId = requireTenantId();
    NotificationTemplateService.Preview preview =
        templateService.preview(
            tenantId,
            request.templateKey(),
            request.locale(),
            request.subject(),
            request.body(),
            request.variablesJson());
    return ResponseEntity.ok(
        new NotificationTemplatePreviewResponse(
            preview.subject(), preview.body(), preview.placeholders()));
  }

  private UUID requireTenantId() {
    return TenantContext.current()
        .map(TenantContext.Context::tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."));
  }

  private UUID actorId() {
    return TenantContext.current()
        .map(TenantContext.Context::userId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."));
  }

  private UUID templateId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "The template id must be a UUID.");
    }
  }

  private NotificationTemplateResponse toResponse(NotificationTemplate template) {
    return new NotificationTemplateResponse(
        template.getId(),
        template.getTemplateKey(),
        template.getLocale(),
        template.getSubject(),
        template.getBody(),
        template.getVersion(),
        template.isActive(),
        template.getCreatedAt(),
        template.getUpdatedAt());
  }

  private NotificationTemplateVersionResponse toVersionResponse(
      NotificationTemplateVersion version) {
    return new NotificationTemplateVersionResponse(
        version.getVersion(),
        version.getSubject(),
        version.getBody(),
        version.isActive(),
        version.getCreatedAt());
  }

  /** Public representation of a tenant notification template. */
  public record NotificationTemplateResponse(
      UUID id,
      String templateKey,
      String locale,
      String subject,
      String body,
      long version,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {}

  /** One audited revision. */
  public record NotificationTemplateVersionResponse(
      long version, String subject, String body, boolean active, Instant createdAt) {}

  /** Create payload. */
  public record CreateNotificationTemplateRequest(
      @NotBlank String templateKey,
      @NotBlank String locale,
      @NotBlank String subject,
      @NotBlank String body) {}

  /** Update payload carrying the revision the editor based its change on. */
  public record UpdateNotificationTemplateRequest(
      @NotNull Long expectedVersion, @NotBlank String subject, @NotBlank String body) {}

  /** Preview payload; subject/body default to the stored template when omitted. */
  public record PreviewNotificationTemplateRequest(
      String templateKey, String locale, String subject, String body, String variablesJson) {}

  /** Rendered preview. */
  public record NotificationTemplatePreviewResponse(
      String subject, String body, List<String> placeholders) {}
}
