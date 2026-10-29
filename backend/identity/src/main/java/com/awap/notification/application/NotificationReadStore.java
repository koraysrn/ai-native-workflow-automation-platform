package com.awap.notification.application;

import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for the tenant-scoped notification read state. Implementations must scope every operation by
 * {@code tenantId} so a cross-tenant identifier can never mutate or expose a delivery.
 */
public interface NotificationReadStore {

  /** Lists the tenant inbox, optionally restricted to unread notifications. */
  List<NotificationDelivery> findAll(UUID tenantId, boolean unreadOnly);

  /** Number of unread notifications for the tenant. */
  long countUnread(UUID tenantId);

  /** Finds one delivery inside the tenant. */
  Optional<NotificationDelivery> findById(UUID tenantId, UUID notificationId);

  /** Marks the delivery read when it is still unread; returns whether a row changed. */
  boolean markRead(UUID tenantId, UUID notificationId, Instant at);

  /** Restores the unread state when the delivery is read; returns whether a row changed. */
  boolean markUnread(UUID tenantId, UUID notificationId, Instant at);
}
