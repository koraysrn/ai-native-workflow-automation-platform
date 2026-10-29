package com.awap.webhook.domain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Durable, replayable record of one WEBHOOK node delivery.
 *
 * <p>Domain rules enforced here: only claimable rows can be leased, only the lease owner may record
 * the attempt outcome, {@code SENT} is terminal, {@code CANCELLED}/{@code DEAD} never go back to a
 * claimable state and the URL, payload and idempotency key are immutable for the lifetime of the
 * record so every retry is byte-identical (and therefore signature-stable).
 */
public final class WebhookDelivery {

  /**
   * Receiver contracts must be able to de-duplicate a 1 MB JSON payload; anything larger is
   * invalid.
   */
  public static final int MAX_PAYLOAD_BYTES = 1_048_576;

  private final UUID id;
  private final UUID tenantId;
  private final UUID workflowInstanceId;
  private final UUID nodeExecutionId;
  private final String nodeId;
  private final String targetUrl;
  private final String payloadJson;
  private final String idempotencyKey;
  private final Instant createdAt;
  private final Instant expiresAt;

  private WebhookDeliveryStatus status;
  private int attempts;
  private int maxAttempts;
  private Instant nextAttemptAt;
  private Instant leaseUntil;
  private String leaseToken;
  private Integer lastStatusCode;
  private String lastError;
  private Instant updatedAt;

  private WebhookDelivery(
      UUID id,
      UUID tenantId,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      String nodeId,
      String targetUrl,
      String payloadJson,
      String idempotencyKey,
      WebhookDeliveryStatus status,
      int attempts,
      int maxAttempts,
      Instant nextAttemptAt,
      Instant leaseUntil,
      String leaseToken,
      Integer lastStatusCode,
      String lastError,
      Instant createdAt,
      Instant updatedAt,
      Instant expiresAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.workflowInstanceId = Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
    this.nodeExecutionId = Objects.requireNonNull(nodeExecutionId, "nodeExecutionId");
    this.nodeId = requireText(nodeId, "nodeId");
    this.targetUrl = requireText(targetUrl, "targetUrl");
    this.payloadJson = requireText(payloadJson, "payloadJson");
    this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
    this.status = Objects.requireNonNull(status, "status");
    this.attempts = Math.max(0, attempts);
    this.maxAttempts = maxAttempts < 1 ? 1 : Math.min(maxAttempts, 10);
    this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    this.leaseUntil = leaseUntil;
    this.leaseToken = leaseToken;
    this.lastStatusCode = lastStatusCode;
    this.lastError = lastError;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (status == WebhookDeliveryStatus.SENT && lastStatusCode == null) {
      throw new IllegalStateException("A SENT delivery must record its last status code.");
    }
  }

  /**
   * Creates a delivery after the (already failed) synchronous first attempt. The first retry is due
   * at {@code nextAttemptAt}; {@code attempts} counts the attempt that just failed.
   */
  public static WebhookDelivery pending(
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
      String lastError,
      Instant now,
      Instant nextAttemptAt,
      Instant expiresAt) {
    Objects.requireNonNull(now, "now");
    Instant due = nextAttemptAt.isBefore(now) ? now : nextAttemptAt;
    return new WebhookDelivery(
        UUID.randomUUID(),
        tenantId,
        workflowInstanceId,
        nodeExecutionId,
        nodeId,
        targetUrl,
        payloadJson,
        idempotencyKey,
        WebhookDeliveryStatus.PENDING,
        attemptsSoFar,
        maxAttempts,
        due,
        null,
        null,
        lastStatusCode,
        lastError,
        now,
        now,
        expiresAt);
  }

  /** Rehydrates a persisted row. State-transition methods guard all later mutations. */
  public static WebhookDelivery rehydrate(
      UUID id,
      UUID tenantId,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      String nodeId,
      String targetUrl,
      String payloadJson,
      String idempotencyKey,
      WebhookDeliveryStatus status,
      int attempts,
      int maxAttempts,
      Instant nextAttemptAt,
      Instant leaseUntil,
      String leaseToken,
      Integer lastStatusCode,
      String lastError,
      Instant createdAt,
      Instant updatedAt,
      Instant expiresAt) {
    return new WebhookDelivery(
        id,
        tenantId,
        workflowInstanceId,
        nodeExecutionId,
        nodeId,
        targetUrl,
        payloadJson,
        idempotencyKey,
        status,
        attempts,
        maxAttempts,
        nextAttemptAt,
        leaseUntil,
        leaseToken,
        lastStatusCode,
        lastError,
        createdAt,
        updatedAt,
        expiresAt);
  }

  /** Convenience factory for the stable receiver-side idempotency key. */
  public static String idempotencyKey(UUID instanceId, String nodeId, UUID nodeExecutionId) {
    return instanceId + ":" + nodeId + ":" + nodeExecutionId;
  }

  /** Leases the row for exactly one HTTP attempt. Fails when another replica holds a live lease. */
  public void claim(String token, Instant now, Duration leaseDuration) {
    requireText(token, "leaseToken");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    if (leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("Lease duration must be positive.");
    }
    if (!status.isClaimable()) {
      throw new IllegalStateException(
          "Delivery " + id + " is " + status + " and cannot be claimed.");
    }
    if (!now.isBefore(expiresAt)) {
      throw new IllegalStateException("Delivery " + id + " has expired and cannot be claimed.");
    }
    if (leaseUntil != null && now.isBefore(leaseUntil)) {
      throw new IllegalStateException("Delivery " + id + " is leased by another replica.");
    }
    this.status = WebhookDeliveryStatus.RETRYING;
    this.attempts = this.attempts + 1;
    this.leaseToken = token;
    this.leaseUntil = now.plus(leaseDuration);
    this.updatedAt = now;
  }

  /** Records a 2xx outcome; the row becomes terminal {@code SENT} and the lease is released. */
  public void recordSent(int statusCode, Instant now) {
    requireLeaseOwner(now);
    this.status = WebhookDeliveryStatus.SENT;
    this.lastStatusCode = statusCode;
    this.lastError = null;
    this.nextAttemptAt = now;
    clearLease();
    this.updatedAt = now;
  }

  /** Schedules the next attempt; a skewed clock can never produce a due instant in the past. */
  public void scheduleRetry(int statusCode, String error, Instant nextAttemptAt, Instant now) {
    requireLeaseOwner(now);
    Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    this.status = WebhookDeliveryStatus.RETRYING;
    this.lastStatusCode = statusCode;
    this.lastError = truncate(error);
    this.nextAttemptAt = nextAttemptAt.isBefore(now) ? now : nextAttemptAt;
    clearLease();
    this.updatedAt = now;
  }

  /** Terminal failure: permanent 4xx, exhausted attempts or an expired row. */
  public void markDead(int statusCode, String error, Instant now) {
    Objects.requireNonNull(now, "now");
    if (status == WebhookDeliveryStatus.SENT) {
      throw new IllegalStateException("A sent delivery cannot become DEAD.");
    }
    this.status = WebhookDeliveryStatus.DEAD;
    this.lastStatusCode = statusCode;
    this.lastError = truncate(error);
    this.nextAttemptAt = now;
    clearLease();
    this.updatedAt = now;
  }

  /** Cancels a workflow-bound delivery so no further attempt is made. */
  public void cancel(Instant now) {
    Objects.requireNonNull(now, "now");
    if (status == WebhookDeliveryStatus.SENT) {
      throw new IllegalStateException("A sent delivery cannot be cancelled.");
    }
    if (status == WebhookDeliveryStatus.CANCELLED) {
      return;
    }
    this.status = WebhookDeliveryStatus.CANCELLED;
    this.nextAttemptAt = now;
    clearLease();
    this.updatedAt = now;
  }

  /** True when the row is due for a claim and holds no live lease. */
  public boolean isDueAt(Instant now) {
    return status.isClaimable()
        && !nextAttemptAt.isAfter(now)
        && now.isBefore(expiresAt)
        && (leaseUntil == null || !now.isBefore(leaseUntil));
  }

  /** True when a live lease is held by somebody else at {@code now}. */
  public boolean isLeasedAt(Instant now) {
    return leaseUntil != null && now.isBefore(leaseUntil);
  }

  public boolean hasExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public boolean hasAttemptsLeft() {
    return attempts < maxAttempts;
  }

  /** Asserts the caller still owns the lease (used by the guarded persistence update). */
  public void requireLeaseOwner(Instant now) {
    if (status != WebhookDeliveryStatus.RETRYING) {
      throw new IllegalStateException("Delivery " + id + " is " + status + ", not RETRYING.");
    }
    if (leaseToken == null) {
      throw new IllegalStateException("Delivery " + id + " holds no lease token.");
    }
    if (leaseUntil != null && !now.isBefore(leaseUntil)) {
      throw new IllegalStateException(
          "Delivery " + id + " lease expired; another replica owns it.");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getWorkflowInstanceId() {
    return workflowInstanceId;
  }

  public UUID getNodeExecutionId() {
    return nodeExecutionId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getTargetUrl() {
    return targetUrl;
  }

  public String getPayloadJson() {
    return payloadJson;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public WebhookDeliveryStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public Optional<Instant> getLeaseUntil() {
    return Optional.ofNullable(leaseUntil);
  }

  public Optional<String> getLeaseToken() {
    return Optional.ofNullable(leaseToken);
  }

  public OptionalInt getLastStatusCode() {
    return lastStatusCode == null ? OptionalInt.empty() : OptionalInt.of(lastStatusCode);
  }

  public Optional<String> getLastError() {
    return Optional.ofNullable(lastError);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  /** Returns the target without its query string so it is safe for logs and the read API. */
  public String sanitizedTargetUrl() {
    int query = targetUrl.indexOf('?');
    int fragment = targetUrl.indexOf('#');
    int cut = query < 0 ? fragment : (fragment < 0 ? query : Math.min(query, fragment));
    return cut < 0 ? targetUrl : targetUrl.substring(0, cut);
  }

  public int payloadSizeBytes() {
    return payloadJson.getBytes(StandardCharsets.UTF_8).length;
  }

  private void clearLease() {
    this.leaseToken = null;
    this.leaseUntil = null;
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    int limit = 2_000;
    return value.length() <= limit ? value : value.substring(0, limit);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank.");
    }
    return value;
  }
}
