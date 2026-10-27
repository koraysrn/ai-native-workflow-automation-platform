package com.awap.webhook.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.webhook.application.WebhookDeliveryService;
import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped operations read API for the durable webhook queue. Errors use RFC 7807 through the
 * central handler; payloads and URL query strings are never exposed.
 */
@RestController
@RequestMapping("/api/v1/webhook-deliveries")
public class WebhookDeliveryController {

  private final WebhookDeliveryService deliveryService;

  public WebhookDeliveryController(WebhookDeliveryService deliveryService) {
    this.deliveryService = deliveryService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<WebhookDeliveryResponse>> list(
      @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
    UUID tenantId =
        TenantContext.current()
            .map(TenantContext.Context::tenantId)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.UNAUTHORIZED,
                        HttpStatus.UNAUTHORIZED,
                        "Authentication is required."));
    return ResponseEntity.ok(
        deliveryService.list(tenantId, parseStatus(status), limit).stream()
            .map(this::toResponse)
            .toList());
  }

  private Optional<WebhookDeliveryStatus> parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(WebhookDeliveryStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ex) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.BAD_REQUEST,
          "Unknown webhook delivery status: " + status);
    }
  }

  private WebhookDeliveryResponse toResponse(WebhookDelivery delivery) {
    return new WebhookDeliveryResponse(
        delivery.getId(),
        delivery.getWorkflowInstanceId(),
        delivery.getNodeExecutionId(),
        delivery.getNodeId(),
        delivery.sanitizedTargetUrl(),
        delivery.getStatus().name(),
        delivery.getAttempts(),
        delivery.getMaxAttempts(),
        delivery.getNextAttemptAt(),
        delivery.getLastStatusCode(),
        delivery.getLastError(),
        delivery.getCreatedAt(),
        delivery.getUpdatedAt(),
        delivery.getExpiresAt());
  }

  /** Public projection: no payload, no query string, no other tenant's identifiers. */
  public record WebhookDeliveryResponse(
      UUID id,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      String nodeId,
      String targetUrl,
      String status,
      int attempts,
      int maxAttempts,
      Instant nextAttemptAt,
      OptionalInt lastStatusCode,
      Optional<String> lastError,
      Instant createdAt,
      Instant updatedAt,
      Instant expiresAt) {}
}
