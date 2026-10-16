package com.awap.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A team that belongs to a department. */
@Entity
@Table(name = "teams")
public class Team {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "department_id", nullable = false)
  private UUID departmentId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Column(nullable = false)
  private boolean deleted;

  protected Team() {}

  public Team(UUID tenantId, UUID departmentId, String name) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.departmentId = departmentId;
    this.name = name;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
    this.deleted = false;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getDepartmentId() {
    return departmentId;
  }

  public String getName() {
    return name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public boolean isDeleted() {
    return deleted;
  }
}
