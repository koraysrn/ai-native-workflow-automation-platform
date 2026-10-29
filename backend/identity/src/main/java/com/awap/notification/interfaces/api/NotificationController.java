package com.awap.notification.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.notification.application.NotificationReadService;
import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped notification inbox, unread count and read/unread transitions. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private final NotificationReadService notificationReadService;

  public NotificationController(NotificationReadService notificationReadService) {
    this.notificationReadService = notificationReadService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<List<NotificationDeliveryResponse>> list(
      @RequestParam(name = "unreadOnly", required = false) String unreadOnly) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(
        notificationReadService.list(tenantId, parseUnreadOnly(unreadOnly)).stream()
            .map(NotificationController::toResponse)
            .toList());
  }

  @GetMapping("/unread-count")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<UnreadCountResponse> unreadCount() {
    return ResponseEntity.ok(
        new UnreadCountResponse(notificationReadService.unreadCount(requireTenantId())));
  }

  @PatchMapping("/{id}/read")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<NotificationDeliveryResponse> markRead(@PathVariable("id") String id) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(
        toResponse(notificationReadService.markRead(tenantId, notificationId(id))));
  }

  @PatchMapping("/{id}/unread")
  @PreAuthorize("hasAuthority('INSTANCE_READ')")
  public ResponseEntity<NotificationDeliveryResponse> markUnread(@PathVariable("id") String id) {
    UUID tenantId = requireTenantId();
    return ResponseEntity.ok(
        toResponse(notificationReadService.markUnread(tenantId, notificationId(id))));
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

  private UUID notificationId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.BAD_REQUEST,
          "The notification id must be a UUID.");
    }
  }

  private boolean parseUnreadOnly(String raw) {
    if (raw == null || raw.isBlank()) {
      return false;
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default ->
          throw new AwapException(
              ErrorCode.VALIDATION_FAILED,
              HttpStatus.BAD_REQUEST,
              "unreadOnly must be true or false.");
    };
  }

  static NotificationDeliveryResponse toResponse(NotificationDelivery delivery) {
    return new NotificationDeliveryResponse(
        delivery.getId(),
        delivery.getChannel().name(),
        delivery.getRecipient(),
        delivery.getTemplateKey(),
        delivery.getStatus(),
        delivery.getAttempts(),
        delivery.getLastError(),
        delivery.getNextAttemptAt(),
        delivery.getCreatedAt(),
        delivery.isRead(),
        delivery.readAt().orElse(null));
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
      Instant nextAttemptAt,
      Instant createdAt,
      boolean read,
      Instant readAt) {}

  /** Unread badge payload. */
  public record UnreadCountResponse(long count) {}
}
