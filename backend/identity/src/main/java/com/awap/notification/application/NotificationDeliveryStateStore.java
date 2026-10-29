package com.awap.notification.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Lease-guarded state transitions for a claimed delivery. Every write is conditional on the lease
 * token and on a retriable status, so a replica whose lease expired (or that races a SENT write)
 * cannot overwrite the winner's outcome.
 */
public interface NotificationDeliveryStateStore {

  /** True when {@code leaseToken} still owns a retriable row. */
  boolean isLeaseCurrent(UUID deliveryId, UUID leaseToken);

  boolean markSent(UUID deliveryId, UUID leaseToken, int attempts, Instant now);

  boolean markRetrying(
      UUID deliveryId,
      UUID leaseToken,
      int attempts,
      String error,
      Instant lastAttemptAt,
      Instant nextAttemptAt);

  boolean markFailedPermanently(
      UUID deliveryId, UUID leaseToken, int attempts, String error, Instant lastAttemptAt);
}
