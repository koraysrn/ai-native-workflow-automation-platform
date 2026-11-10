package com.awap.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable audit record for organization and role changes (issue #194). */
@Entity
@Table(name = "organization_audit_events")
public class OrganizationAuditEvent {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "entity_type", nullable = false)
  private String entityType;

  @Column(name = "entity_id", nullable = false)
  private UUID entityId;

  @Column(nullable = false)
  private String action;

  @Column(name = "old_value")
  private String oldValue;

  @Column(name = "new_value")
  private String newValue;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected OrganizationAuditEvent() {}

  public OrganizationAuditEvent(
      UUID tenantId,
      String entityType,
      UUID entityId,
      String action,
      String oldValue,
      String newValue,
      UUID actorId) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.entityType = entityType;
    this.entityId = entityId;
    this.action = action;
    this.oldValue = oldValue;
    this.newValue = newValue;
    this.actorId = actorId;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getEntityType() {
    return entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public String getAction() {
    return action;
  }

  public String getOldValue() {
    return oldValue;
  }

  public String getNewValue() {
    return newValue;
  }

  public UUID getActorId() {
    return actorId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
