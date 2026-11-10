package com.awap.organization.domain;

import com.awap.identity.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** A job position within a tenant; the role drives the effective authorities of its members. */
@Entity
@Table(name = "positions")
public class Position {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  /** Optimistic locking: a concurrent role update is rejected instead of silently overwriting. */
  @Version
  @Column(nullable = false)
  private long version;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  @LastModifiedBy
  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(nullable = false)
  private boolean deleted;

  protected Position() {}

  public Position(UUID tenantId, String name) {
    this(tenantId, name, Role.EMPLOYEE);
  }

  /** A missing (null) role is stored as the safe EMPLOYEE default. */
  public Position(UUID tenantId, String name, Role role) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.name = name;
    this.role = role == null ? Role.EMPLOYEE : role;
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

  public String getName() {
    return name;
  }

  public Role getRole() {
    return role;
  }

  /** Changes the position role. The domain rejects a null role instead of corrupting the row. */
  public void changeRole(Role role) {
    if (role == null) {
      throw new IllegalArgumentException("A position role is required");
    }
    this.role = role;
    this.updatedAt = Instant.now();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
