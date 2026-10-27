package com.awap.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Immutable audit row for one saved revision of a notification template. */
@Entity
@Table(
    name = "notification_template_versions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_notification_template_versions",
            columnNames = {"template_id", "version"}))
public class NotificationTemplateVersion {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "template_id", nullable = false)
  private UUID templateId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private long version;

  @Column(nullable = false)
  private String subject;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String body;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected NotificationTemplateVersion() {}

  public NotificationTemplateVersion(NotificationTemplate template) {
    this.id = UUID.randomUUID();
    this.templateId = template.getId();
    this.tenantId = template.getTenantId();
    this.version = template.getVersion();
    this.subject = template.getSubject();
    this.body = template.getBody();
    this.active = template.isActive();
    this.createdBy = template.getUpdatedBy();
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTemplateId() {
    return templateId;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public long getVersion() {
    return version;
  }

  public String getSubject() {
    return subject;
  }

  public String getBody() {
    return body;
  }

  public boolean isActive() {
    return active;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
