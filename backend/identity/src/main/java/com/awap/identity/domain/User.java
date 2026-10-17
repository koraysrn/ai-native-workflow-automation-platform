package com.awap.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** A user belongs to exactly one tenant. */
@Entity
@Table(name = "users")
public class User {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

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

  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(nullable = false)
  private boolean deleted;

  protected User() {}

  public User(UUID tenantId, String email, String passwordHash, String fullName, Role role) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.email = email;
    this.passwordHash = passwordHash;
    this.fullName = fullName;
    this.role = role;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
    this.deleted = false;
    this.failedLoginAttempts = 0;
    this.lockedUntil = null;
  }

  public boolean isLocked() {
    return lockedUntil != null && lockedUntil.isAfter(Instant.now());
  }

  public void registerFailedLogin() {
    failedLoginAttempts++;
    if (failedLoginAttempts >= 5) {
      lockedUntil = Instant.now().plus(Duration.ofMinutes(15));
      failedLoginAttempts = 0;
    }
  }

  public void resetFailedLogin() {
    failedLoginAttempts = 0;
    lockedUntil = null;
  }

  public void assignRole(Role role) {
    this.role = role;
    this.updatedAt = Instant.now();
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

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getFullName() {
    return fullName;
  }

  public Role getRole() {
    return role;
  }

  public boolean isDeleted() {
    return deleted;
  }
}
