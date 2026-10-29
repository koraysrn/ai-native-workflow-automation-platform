package com.awap.webhook.application;

import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary and use-case orchestration for durable webhook deliveries. All writes are
 * tenant-scoped (the store sets {@code awap.tenant_id} for RLS) and idempotent on {@code
 * (tenant_id, idempotency_key)}.
 */
@Service
public class WebhookDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

  private final WebhookDeliveryStore store;
  private final WebhookRetryPolicy policy;
  private final int retentionDays;
  private final int batchSize;
  private final Duration leaseDuration;

  @Autowired
  public WebhookDeliveryService(
      WebhookDeliveryStore store,
      WebhookRetryPolicy policy,
      @Value("${awap.webhook.retry.retention-days:7}") int retentionDays,
      @Value("${awap.webhook.retry.batch-size:25}") int batchSize,
      @Value("${awap.webhook.retry.lease-seconds:30}") int leaseSeconds) {
    this(store, policy, retentionDays, batchSize, Duration.ofSeconds(Math.max(1, leaseSeconds)));
  }

  public WebhookDeliveryService(
      WebhookDeliveryStore store,
      WebhookRetryPolicy policy,
      int retentionDays,
      int batchSize,
      Duration leaseDuration) {
    this.store = store;
    this.policy = policy;
    this.retentionDays = Math.max(1, retentionDays);
    this.batchSize = Math.max(1, Math.min(batchSize, 500));
    this.leaseDuration = leaseDuration;
  }

  public WebhookRetryPolicy policy() {
    return policy;
  }

  public Duration leaseDuration() {
    return leaseDuration;
  }

  /**
   * Persists a durable retry after the synchronous first attempt failed with a retryable error.
   * Re-uses an existing row for the same tenant/idempotency key instead of creating a duplicate.
   */
  @Transactional
  public WebhookDelivery scheduleRetryAfterFailure(PendingDelivery request, Instant now) {
    Optional<WebhookDelivery> existing = findExisting(request);
    if (existing.isPresent()) {
      return existing.get();
    }
    Duration delay = policy.applyJitter(policy.delayBeforeRetry(request.attemptsSoFar()));
    WebhookDelivery delivery =
        WebhookDelivery.pending(
            request.tenantId(),
            request.workflowInstanceId(),
            request.nodeExecutionId(),
            request.nodeId(),
            request.targetUrl(),
            request.payloadJson(),
            request.idempotencyKey(),
            request.attemptsSoFar(),
            request.maxAttempts(),
            request.lastStatusCode(),
            request.lastError(),
            now,
            now.plus(delay),
            now.plus(Duration.ofDays(retentionDays)));
    return saveIdempotently(delivery, request);
  }

  /** Persists a DEAD row for a permanent first failure so operators can inspect it. */
  @Transactional
  public WebhookDelivery recordPermanentFailure(PendingDelivery request, Instant now) {
    Optional<WebhookDelivery> existing = findExisting(request);
    if (existing.isPresent()) {
      return existing.get();
    }
    WebhookDelivery delivery =
        WebhookDelivery.pending(
            request.tenantId(),
            request.workflowInstanceId(),
            request.nodeExecutionId(),
            request.nodeId(),
            request.targetUrl(),
            request.payloadJson(),
            request.idempotencyKey(),
            request.attemptsSoFar(),
            request.maxAttempts(),
            request.lastStatusCode(),
            request.lastError(),
            now,
            now,
            now.plus(Duration.ofDays(retentionDays)));
    delivery.markDead(request.lastStatusCode(), request.lastError(), now);
    return saveIdempotently(delivery, request);
  }

  /** Claims due rows atomically; every returned row carries a fresh lease token and attempts+1. */
  @Transactional
  public List<WebhookDelivery> claimDue(Instant now) {
    return store.claimDue(now, batchSize, leaseDuration);
  }

  @Transactional
  public boolean recordSent(WebhookDelivery claimed, int statusCode, Instant updatedAt) {
    return store.recordSent(
        claimed.getId(),
        claimed.getTenantId(),
        claimed.getLeaseToken().orElseThrow(),
        statusCode,
        updatedAt);
  }

  @Transactional
  public boolean recordRetry(
      WebhookDelivery claimed, int statusCode, String error, Instant nextAttemptAt, Instant now) {
    return store.recordRetry(
        claimed.getId(),
        claimed.getTenantId(),
        claimed.getLeaseToken().orElseThrow(),
        statusCode,
        error,
        nextAttemptAt,
        now);
  }

  @Transactional
  public boolean recordDead(WebhookDelivery claimed, int statusCode, String error, Instant now) {
    return store.recordDead(
        claimed.getId(),
        claimed.getTenantId(),
        claimed.getLeaseToken().orElseThrow(),
        statusCode,
        error,
        now);
  }

  /** Marks every open delivery of a cancelled workflow instance CANCELLED. */
  @Transactional
  public int cancelForInstance(UUID tenantId, UUID instanceId, Instant now) {
    int cancelled = store.cancelByInstance(tenantId, instanceId, now);
    if (cancelled > 0) {
      log.info("Cancelled {} webhook deliver(ies) for instance {}", cancelled, instanceId);
    }
    return cancelled;
  }

  /** Expires stale rows so they never keep retrying; the scheduler invokes this every cycle. */
  @Transactional
  public int expireStale(Instant now) {
    return store.cancelExpired(now);
  }

  /** Tenant-scoped read used by the operations API; never returns another tenant's rows. */
  public List<WebhookDelivery> list(
      UUID tenantId, Optional<WebhookDeliveryStatus> status, int limit) {
    int bounded = Math.max(1, Math.min(limit, 100));
    return store.findByTenant(tenantId, status, bounded);
  }

  private Optional<WebhookDelivery> findExisting(PendingDelivery request) {
    return store.findByTenantIdAndIdempotencyKey(request.tenantId(), request.idempotencyKey());
  }

  private WebhookDelivery saveIdempotently(WebhookDelivery delivery, PendingDelivery request) {
    try {
      return store.save(delivery);
    } catch (DataIntegrityViolationException ex) {
      // A concurrent node re-execution inserted the same (tenant, idempotency key) first.
      return store
          .findByTenantIdAndIdempotencyKey(request.tenantId(), request.idempotencyKey())
          .orElseThrow(() -> ex);
    }
  }

  /** Immutable input for persisting a delivery after the synchronous first attempt. */
  public record PendingDelivery(
      UUID tenantId,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      String nodeId,
      String targetUrl,
      String payloadJson,
      String idempotencyKey,
      int attemptsSoFar,
      int maxAttempts,
      int lastStatusCode,
      String lastError) {}
}
