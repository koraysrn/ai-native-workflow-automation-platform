package com.awap.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable per-message delivery record used for de-duplication, audit, the in-app inbox and the
 * retry schedule.
 *
 * <p>The row is written before the first provider call, so a consumer crash cannot lose the
 * delivery: the scheduler picks the row up again once {@code nextAttemptAt} passes.
 *
 * <p>Uniqueness is scoped to the active de-duplication window: the database trigger retires
 * terminal rows once {@code dedupeExpiresAt} passes, and {@link #isDedupeActiveAt(Instant)}
 * implements the same inclusive boundary in the application so a cached row cannot suppress a
 * legitimate repeat.
 */
@Entity
@Table(
    name = "notification_deliveries",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_notification_deliveries",
            columnNames = {"tenant_id", "dedupe_key"}))
public class NotificationDelivery {

  public static final String PENDING = "PENDING";
  public static final String RETRYING = "RETRYING";
  public static final String SENT = "SENT";
  public static final String FAILED = "FAILED";

  private static final long DEFAULT_DEDUPE_WINDOW_SECONDS = 3_600L;

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "dedupe_key", nullable = false)
  private String dedupeKey;

  /**
   * Business identity (workflow instance / task / approval / form) when the producer supplied one.
   */
  @Column(name = "business_key", columnDefinition = "TEXT")
  private String businessKey;

  /** End of the active de-duplication window; the key is reusable strictly after this instant. */
  @Column(name = "dedupe_expires_at", nullable = false)
  private Instant dedupeExpiresAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private NotificationChannel channel;

  @Column(nullable = false)
  private String recipient;

  @Column(name = "template_key")
  private String templateKey;

  /** Locale captured on the first attempt; retries must not re-resolve it. */
  @Column(nullable = false, length = 20)
  private String locale;

  /** Variables captured on the first attempt; retries must render the same body. */
  @Column(name = "variables_json", columnDefinition = "TEXT")
  private String variablesJson;

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "lease_token")
  private UUID leaseToken;

  @Column(name = "last_attempt_at")
  private Instant lastAttemptAt;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected NotificationDelivery() {}

  public NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey) {
    this(
        tenantId,
        dedupeKey,
        channel,
        recipient,
        templateKey,
        "en",
        null,
        null,
        Instant.now().plusSeconds(DEFAULT_DEDUPE_WINDOW_SECONDS));
  }

  /** Retry-scheduler constructor: locale and variables are captured for a stable re-render. */
  public NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String locale,
      String variablesJson) {
    this(
        tenantId,
        dedupeKey,
        channel,
        recipient,
        templateKey,
        locale,
        variablesJson,
        null,
        Instant.now().plusSeconds(DEFAULT_DEDUPE_WINDOW_SECONDS));
  }

  /** De-duplication constructor: caller decides the window and the business identity. */
  public NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String businessKey,
      Instant dedupeExpiresAt,
      Instant createdAt) {
    this(
        tenantId,
        dedupeKey,
        channel,
        recipient,
        templateKey,
        "en",
        null,
        businessKey,
        dedupeExpiresAt,
        createdAt);
  }

  public NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String locale,
      String variablesJson,
      String businessKey,
      Instant dedupeExpiresAt) {
    this(
        tenantId,
        dedupeKey,
        channel,
        recipient,
        templateKey,
        locale,
        variablesJson,
        businessKey,
        dedupeExpiresAt,
        Instant.now());
  }

  private NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String locale,
      String variablesJson,
      String businessKey,
      Instant dedupeExpiresAt,
      Instant createdAt) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.dedupeKey = dedupeKey;
    this.channel = channel;
    this.recipient = recipient;
    this.templateKey = templateKey;
    this.locale = locale == null || locale.isBlank() ? "en" : locale;
    this.variablesJson = variablesJson;
    this.businessKey = businessKey;
    this.dedupeExpiresAt = dedupeExpiresAt;
    this.status = PENDING;
    this.attempts = 0;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  /** Active-window check; the TTL boundary itself is still inside the window. */
  public boolean isDedupeActiveAt(Instant at) {
    return dedupeExpiresAt != null && !at.isAfter(dedupeExpiresAt);
  }

  /** Remaining window; a rolled-back clock can make it larger but never negative. */
  public Duration dedupeWindowRemaining(Instant at) {
    if (dedupeExpiresAt == null) {
      return Duration.ZERO;
    }
    Duration remaining = Duration.between(at, dedupeExpiresAt);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  /**
   * Marks the row as waiting for the scheduler. The grace window keeps the consumer's in-flight
   * first attempt from being duplicated by the scheduler.
   */
  public void scheduleFirstAttempt(Instant earliestRetryAt) {
    this.status = PENDING;
    this.nextAttemptAt = earliestRetryAt;
    this.leaseUntil = null;
    this.leaseToken = null;
    this.updatedAt = Instant.now();
  }

  public void recordAttempt(Instant at) {
    this.attempts++;
    this.lastAttemptAt = at;
    this.updatedAt = at;
  }

  public void recordAttempt() {
    recordAttempt(Instant.now());
  }

  public void markSent(Instant at) {
    this.status = SENT;
    this.lastError = null;
    this.nextAttemptAt = null;
    this.leaseUntil = null;
    this.leaseToken = null;
    this.updatedAt = at;
  }

  public void markSent() {
    markSent(Instant.now());
  }

  /** Retains the row for a later scheduler attempt. */
  public void markRetrying(String error, Instant nextAttemptAt, Instant at) {
    this.status = RETRYING;
    this.lastError = error;
    this.nextAttemptAt = nextAttemptAt;
    this.leaseUntil = null;
    this.leaseToken = null;
    this.updatedAt = at;
  }

  /** Terminal failure: no further attempts, the row stays for audit until retention purges it. */
  public void markFailedPermanently(String error, Instant at) {
    this.status = FAILED;
    this.lastError = error;
    this.nextAttemptAt = null;
    this.leaseUntil = null;
    this.leaseToken = null;
    this.updatedAt = at;
  }

  public void markFailed(String error) {
    markFailedPermanently(error, Instant.now());
  }

  /**
   * Acknowledges the in-app notification. Idempotent: an already-read delivery keeps the original
   * {@code read_at} so repeated calls cannot shift the retention clock.
   *
   * @return {@code true} when the state actually changed
   */
  public boolean markRead(Instant at) {
    if (readAt != null) {
      return false;
    }
    this.readAt = at;
    this.updatedAt = at;
    return true;
  }

  /**
   * Restores the unread state. Idempotent: an already-unread delivery is left untouched.
   *
   * @return {@code true} when the state actually changed
   */
  public boolean markUnread(Instant at) {
    if (readAt == null) {
      return false;
    }
    this.readAt = null;
    this.updatedAt = at;
    return true;
  }

  public boolean isRead() {
    return readAt != null;
  }

  public boolean isSent() {
    return SENT.equals(status);
  }

  public boolean isTerminal() {
    return SENT.equals(status) || FAILED.equals(status);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getDedupeKey() {
    return dedupeKey;
  }

  public String getBusinessKey() {
    return businessKey;
  }

  public Instant getDedupeExpiresAt() {
    return dedupeExpiresAt;
  }

  public NotificationChannel getChannel() {
    return channel;
  }

  public String getRecipient() {
    return recipient;
  }

  public String getTemplateKey() {
    return templateKey;
  }

  public String getLocale() {
    return locale;
  }

  public String getVariablesJson() {
    return variablesJson;
  }

  public String getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public UUID getLeaseToken() {
    return leaseToken;
  }

  public Instant getLastAttemptAt() {
    return lastAttemptAt;
  }

  /** Read timestamp, empty while the notification is unread. */
  public Optional<Instant> readAt() {
    return Optional.ofNullable(readAt);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
