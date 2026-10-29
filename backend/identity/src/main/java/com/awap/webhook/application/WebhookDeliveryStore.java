package com.awap.webhook.application;

import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the durable webhook delivery queue.
 *
 * <p>Claiming is atomic and lease-based: an implementation must guarantee that two concurrent
 * claimers can never receive the same row (PostgreSQL: {@code FOR UPDATE SKIP LOCKED}). Mutations
 * are guarded by the lease token so a replica whose lease expired can never overwrite the outcome
 * recorded by the new owner.
 */
public interface WebhookDeliveryStore {

  WebhookDelivery save(WebhookDelivery delivery);

  Optional<WebhookDelivery> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

  /** Claims due rows, increments attempts and stamps a fresh lease token on each returned row. */
  List<WebhookDelivery> claimDue(Instant now, int limit, Duration leaseDuration);

  boolean recordSent(UUID id, UUID tenantId, String leaseToken, int statusCode, Instant updatedAt);

  boolean recordRetry(
      UUID id,
      UUID tenantId,
      String leaseToken,
      int statusCode,
      String error,
      Instant nextAttemptAt,
      Instant updatedAt);

  boolean recordDead(
      UUID id, UUID tenantId, String leaseToken, int statusCode, String error, Instant updatedAt);

  List<WebhookDelivery> findByTenant(
      UUID tenantId, Optional<WebhookDeliveryStatus> status, int limit);

  int cancelByInstance(UUID tenantId, UUID instanceId, Instant updatedAt);

  int cancelExpired(Instant now);
}
