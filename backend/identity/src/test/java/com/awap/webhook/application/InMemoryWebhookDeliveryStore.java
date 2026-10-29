package com.awap.webhook.application;

import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * In-memory implementation of the delivery store used by unit tests. It mirrors the PostgreSQL
 * semantics that matter: atomic claim, lease-token guarded mutation, unique idempotency key and
 * tenant-scoped reads. All mutating methods are synchronized so the concurrency tests exercise the
 * "exactly one claimant wins" rule without a database.
 */
public final class InMemoryWebhookDeliveryStore implements WebhookDeliveryStore {

  private final List<WebhookDelivery> rows = new ArrayList<>();
  private int saveCalls;
  private RuntimeException nextSaveFailure;
  private WebhookDelivery insertThenFail;

  @Override
  public synchronized WebhookDelivery save(WebhookDelivery delivery) {
    saveCalls++;
    if (nextSaveFailure != null) {
      RuntimeException failure = nextSaveFailure;
      nextSaveFailure = null;
      throw failure;
    }
    if (insertThenFail != null) {
      WebhookDelivery winner = insertThenFail;
      insertThenFail = null;
      rows.add(winner);
      throw new DataIntegrityViolationException("concurrent insert won the unique constraint");
    }
    boolean duplicate =
        rows.stream()
            .anyMatch(
                row ->
                    row.getTenantId().equals(delivery.getTenantId())
                        && row.getIdempotencyKey().equals(delivery.getIdempotencyKey()));
    if (duplicate) {
      throw new DataIntegrityViolationException("duplicate idempotency key");
    }
    rows.add(delivery);
    return delivery;
  }

  synchronized void failNextSaveWith(RuntimeException failure) {
    this.nextSaveFailure = failure;
  }

  /** Simulates losing the unique-key race: the competing row exists after our INSERT fails. */
  synchronized void insertThenFailOnNextSave(WebhookDelivery competingRow) {
    this.insertThenFail = competingRow;
  }

  @Override
  public synchronized Optional<WebhookDelivery> findByTenantIdAndIdempotencyKey(
      UUID tenantId, String idempotencyKey) {
    return rows.stream()
        .filter(row -> row.getTenantId().equals(tenantId))
        .filter(row -> row.getIdempotencyKey().equals(idempotencyKey))
        .findFirst();
  }

  @Override
  public synchronized List<WebhookDelivery> claimDue(
      Instant now, int limit, Duration leaseDuration) {
    List<WebhookDelivery> claimed = new ArrayList<>();
    for (WebhookDelivery row : rows) {
      if (claimed.size() >= Math.max(1, limit)) {
        break;
      }
      if (!row.isDueAt(now)) {
        continue;
      }
      row.claim("lease-" + UUID.randomUUID(), now, leaseDuration);
      // The database hands every replica its own snapshot; a stale claimer must not mutate the
      // authoritative row, so the fake returns copies as well.
      claimed.add(copyOf(row));
    }
    return claimed;
  }

  private static WebhookDelivery copyOf(WebhookDelivery row) {
    return WebhookDelivery.rehydrate(
        row.getId(),
        row.getTenantId(),
        row.getWorkflowInstanceId(),
        row.getNodeExecutionId(),
        row.getNodeId(),
        row.getTargetUrl(),
        row.getPayloadJson(),
        row.getIdempotencyKey(),
        row.getStatus(),
        row.getAttempts(),
        row.getMaxAttempts(),
        row.getNextAttemptAt(),
        row.getLeaseUntil().orElse(null),
        row.getLeaseToken().orElse(null),
        row.getLastStatusCode().isPresent() ? row.getLastStatusCode().getAsInt() : null,
        row.getLastError().orElse(null),
        row.getCreatedAt(),
        row.getUpdatedAt(),
        row.getExpiresAt());
  }

  @Override
  public synchronized boolean recordSent(
      UUID id, UUID tenantId, String leaseToken, int statusCode, Instant updatedAt) {
    Optional<WebhookDelivery> row = find(id, tenantId);
    if (row.isEmpty() || !ownsLease(row.get(), leaseToken)) {
      return false;
    }
    try {
      row.get().recordSent(statusCode, updatedAt);
      return true;
    } catch (IllegalStateException ex) {
      return false;
    }
  }

  @Override
  public synchronized boolean recordRetry(
      UUID id,
      UUID tenantId,
      String leaseToken,
      int statusCode,
      String error,
      Instant nextAttemptAt,
      Instant updatedAt) {
    Optional<WebhookDelivery> row = find(id, tenantId);
    if (row.isEmpty() || !ownsLease(row.get(), leaseToken)) {
      return false;
    }
    try {
      row.get().scheduleRetry(statusCode, error, nextAttemptAt, updatedAt);
      return true;
    } catch (IllegalStateException ex) {
      return false;
    }
  }

  @Override
  public synchronized boolean recordDead(
      UUID id, UUID tenantId, String leaseToken, int statusCode, String error, Instant updatedAt) {
    Optional<WebhookDelivery> row = find(id, tenantId);
    if (row.isEmpty() || !ownsLease(row.get(), leaseToken)) {
      return false;
    }
    try {
      row.get().markDead(statusCode, error, updatedAt);
      return true;
    } catch (IllegalStateException ex) {
      return false;
    }
  }

  @Override
  public synchronized List<WebhookDelivery> findByTenant(
      UUID tenantId, Optional<WebhookDeliveryStatus> status, int limit) {
    return rows.stream()
        .filter(row -> row.getTenantId().equals(tenantId))
        .filter(row -> status.map(expected -> row.getStatus() == expected).orElse(true))
        .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
        .limit(Math.max(1, limit))
        .toList();
  }

  @Override
  public synchronized int cancelByInstance(UUID tenantId, UUID instanceId, Instant updatedAt) {
    int cancelled = 0;
    for (WebhookDelivery row : rows) {
      if (row.getTenantId().equals(tenantId)
          && row.getWorkflowInstanceId().equals(instanceId)
          && row.getStatus().isClaimable()) {
        row.cancel(updatedAt);
        cancelled++;
      }
    }
    return cancelled;
  }

  @Override
  public synchronized int cancelExpired(Instant now) {
    int cancelled = 0;
    for (WebhookDelivery row : rows) {
      if (row.getStatus().isClaimable() && row.hasExpired(now) && !row.isLeasedAt(now)) {
        row.cancel(now);
        cancelled++;
      }
    }
    return cancelled;
  }

  public synchronized List<WebhookDelivery> allRows() {
    return List.copyOf(rows);
  }

  synchronized int saveCalls() {
    return saveCalls;
  }

  private Optional<WebhookDelivery> find(UUID id, UUID tenantId) {
    return rows.stream()
        .filter(row -> row.getId().equals(id))
        .filter(row -> row.getTenantId().equals(tenantId))
        .findFirst();
  }

  private boolean ownsLease(WebhookDelivery row, String leaseToken) {
    return row.getLeaseToken().map(token -> token.equals(leaseToken)).orElse(false);
  }
}
