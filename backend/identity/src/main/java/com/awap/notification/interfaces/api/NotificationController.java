package com.awap.notification.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.notification.application.NotificationService;
import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped notification inbox and delivery history. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<NotificationDeliveryResponse>> list() {
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
        notificationService.deliveries(tenantId).stream().map(this::toResponse).toList());
  }

  private NotificationDeliveryResponse toResponse(NotificationDelivery delivery) {
    return new NotificationDeliveryResponse(
        delivery.getId(),
        delivery.getChannel().name(),
        delivery.getRecipient(),
        delivery.getTemplateKey(),
        delivery.getStatus(),
        delivery.getAttempts(),
        delivery.getLastError(),
        delivery.getCreatedAt());
  }

  /** Public representation of a notification delivery. */
  public record NotificationDeliveryResponse(
      UUID id,
      String channel,
      String recipient,
      String templateKey,
      String status,
      int attempts,
      String lastError,
      Instant createdAt) {}
}
