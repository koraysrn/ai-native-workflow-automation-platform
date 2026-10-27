package com.awap.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable per-message delivery record used for de-duplication, audit, the in-app inbox and the
 * retry schedule.
 *
 * <p>The row is written before the first provider call, so a consumer crash cannot lose the
 * delivery: the scheduler picks the row up again once {@code nextAttemptAt} passes.
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

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "dedupe_key", nullable = false)
  private String dedupeKey;

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

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NotificationDelivery() {}

  public NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey) {
    this(tenantId, dedupeKey, channel, recipient, templateKey, "en", null);
  }

  public NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String locale,
      String variablesJson) {
    Instant now = Instant.now();
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.dedupeKey = dedupeKey;
    this.channel = channel;
    this.recipient = recipient;
    this.templateKey = templateKey;
    this.locale = locale == null || locale.isBlank() ? "en" : locale;
    this.variablesJson = variablesJson;
    this.status = PENDING;
    this.attempts = 0;
    this.createdAt = now;
    this.updatedAt = now;
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

  public void markSent(Instant at) {
    this.status = SENT;
    this.lastError = null;
    this.nextAttemptAt = null;
    this.leaseUntil = null;
    this.leaseToken = null;
    this.updatedAt = at;
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

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
