package com.awap.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable per-message delivery record used for de-duplication, audit and in-app notifications.
 *
 * <p>Uniqueness is scoped to the active window: the DB trigger retires terminal rows once {@code
 * dedupeExpiresAt} passes, and {@link #isDedupeActiveAt(Instant)} implements the same inclusive
 * boundary in the application so a cached row cannot suppress a legitimate repeat.
 */
@Entity
@Table(
    name = "notification_deliveries",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_notification_deliveries",
            columnNames = {"tenant_id", "dedupe_key"}))
public class NotificationDelivery {

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

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError;

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
    this(
        tenantId,
        dedupeKey,
        channel,
        recipient,
        templateKey,
        null,
        Instant.now().plusSeconds(3600),
        Instant.now());
  }

  public NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String businessKey,
      Instant dedupeExpiresAt,
      Instant createdAt) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.dedupeKey = dedupeKey;
    this.channel = channel;
    this.recipient = recipient;
    this.templateKey = templateKey;
    this.businessKey = businessKey;
    this.dedupeExpiresAt = dedupeExpiresAt;
    this.status = "PENDING";
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

  public void recordAttempt() {
    this.attempts++;
    this.updatedAt = Instant.now();
  }

  public void markSent() {
    this.status = "SENT";
    this.lastError = null;
    this.updatedAt = Instant.now();
  }

  public void markFailed(String error) {
    this.status = "FAILED";
    this.lastError = error;
    this.updatedAt = Instant.now();
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

  public String getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
