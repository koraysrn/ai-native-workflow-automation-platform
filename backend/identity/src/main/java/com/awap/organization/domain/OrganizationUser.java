package com.awap.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A person placed in the organization hierarchy (team + position). */
@Entity
@Table(name = "organization_users")
public class OrganizationUser {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String email;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Column(name = "team_id")
  private UUID teamId;

  @Column(name = "position_id")
  private UUID positionId;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Column(nullable = false)
  private boolean deleted;

  protected OrganizationUser() {}

  public OrganizationUser(
      UUID tenantId, String email, String fullName, UUID teamId, UUID positionId) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.email = email;
    this.fullName = fullName;
    this.teamId = teamId;
    this.positionId = positionId;
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

  public String getEmail() {
    return email;
  }

  public String getFullName() {
    return fullName;
  }

  public UUID getTeamId() {
    return teamId;
  }

  public UUID getPositionId() {
    return positionId;
  }

  public void assignToTeam(UUID teamId) {
    this.teamId = teamId;
    this.updatedAt = Instant.now();
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
