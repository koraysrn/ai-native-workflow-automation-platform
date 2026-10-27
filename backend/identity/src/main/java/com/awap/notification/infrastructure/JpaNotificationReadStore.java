package com.awap.notification.infrastructure;

import com.awap.notification.application.NotificationReadStore;
import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** JPA adapter for the notification read-state port. */
@Repository
public class JpaNotificationReadStore implements NotificationReadStore {

  private final NotificationDeliveryRepository repository;

  public JpaNotificationReadStore(NotificationDeliveryRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<NotificationDelivery> findAll(UUID tenantId, boolean unreadOnly) {
    return unreadOnly
        ? repository.findByTenantIdAndReadAtIsNullOrderByCreatedAtDesc(tenantId)
        : repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
  }

  @Override
  public long countUnread(UUID tenantId) {
    return repository.countByTenantIdAndReadAtIsNull(tenantId);
  }

  @Override
  public Optional<NotificationDelivery> findById(UUID tenantId, UUID notificationId) {
    return repository
        .findById(notificationId)
        .filter(delivery -> tenantId.equals(delivery.getTenantId()));
  }

  @Override
  public boolean markRead(UUID tenantId, UUID notificationId, Instant at) {
    return repository.markRead(notificationId, tenantId, at) > 0;
  }

  @Override
  public boolean markUnread(UUID tenantId, UUID notificationId, Instant at) {
    return repository.markUnread(notificationId, tenantId, at) > 0;
  }
}
