package com.awap.notification.infrastructure;

import com.awap.notification.application.NotificationDeliveryStateStore;
import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Lease-guarded state transitions backed by conditional JPQL updates. The UPDATE itself carries the
 * status guard, so it is safe against replicas that lost their lease mid-flight.
 */
@Component
public class JpaNotificationDeliveryStateStore implements NotificationDeliveryStateStore {

  private static final List<String> RETRIABLE =
      List.of(NotificationDelivery.PENDING, NotificationDelivery.RETRYING);

  private final NotificationDeliveryRepository repository;

  public JpaNotificationDeliveryStateStore(NotificationDeliveryRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean isLeaseCurrent(UUID deliveryId, UUID leaseToken) {
    return repository.existsByIdAndLeaseTokenAndStatusIn(deliveryId, leaseToken, RETRIABLE);
  }

  @Override
  public boolean markSent(UUID deliveryId, UUID leaseToken, int attempts, Instant now) {
    return repository.applySent(deliveryId, leaseToken, attempts, now) == 1;
  }

  @Override
  public boolean markRetrying(
      UUID deliveryId,
      UUID leaseToken,
      int attempts,
      String error,
      Instant lastAttemptAt,
      Instant nextAttemptAt) {
    return repository.applyRetrying(
            deliveryId, leaseToken, attempts, error, lastAttemptAt, nextAttemptAt)
        == 1;
  }

  @Override
  public boolean markFailedPermanently(
      UUID deliveryId, UUID leaseToken, int attempts, String error, Instant lastAttemptAt) {
    return repository.applyFailed(deliveryId, leaseToken, attempts, error, lastAttemptAt) == 1;
  }
}
