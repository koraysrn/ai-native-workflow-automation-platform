package com.awap.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant-owned notification template for one key/locale pair. The {@code version} column is the
 * optimistic locking token: every revision increments it and the previous revision is preserved in
 * {@link NotificationTemplateVersion}.
 */
@Entity
@Table(
    name = "notification_templates",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_notification_templates",
            columnNames = {"tenant_id", "template_key", "locale"}))
public class NotificationTemplate {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "template_key", nullable = false)
  private String templateKey;

  @Column(nullable = false)
  private String locale;

  @Column(nullable = false)
  private String subject;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String body;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NotificationTemplate() {}

  public NotificationTemplate(
      UUID tenantId, String templateKey, String locale, String subject, String body, UUID actor) {
    NotificationTemplateValidator.validate(subject, body);
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.templateKey = templateKey;
    this.locale = locale;
    this.subject = subject;
    this.body = body;
    this.active = true;
    this.createdBy = actor;
    this.updatedBy = actor;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  /** Stores a new revision of the template content. */
  public void revise(String subject, String body, UUID actor) {
    NotificationTemplateValidator.validate(subject, body);
    this.subject = subject;
    this.body = body;
    this.updatedBy = actor;
    this.updatedAt = Instant.now();
  }

  /** Deactivates the template; idempotent. */
  public boolean archive(UUID actor) {
    if (!active) {
      return false;
    }
    this.active = false;
    this.updatedBy = actor;
    this.updatedAt = Instant.now();
    return true;
  }

  /** Re-activates the template; idempotent. */
  public boolean activate(UUID actor) {
    if (active) {
      return false;
    }
    this.active = true;
    this.updatedBy = actor;
    this.updatedAt = Instant.now();
    return true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getTemplateKey() {
    return templateKey;
  }

  public String getLocale() {
    return locale;
  }

  public String getSubject() {
    return subject;
  }

  public String getBody() {
    return body;
  }

  public long getVersion() {
    return version;
  }

  public boolean isActive() {
    return active;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
