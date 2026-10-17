package com.awap.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tenant-scoped invitation that grants a role once accepted. */
@Entity
@Table(name = "invitations")
public class Invitation {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Column(name = "token_hash", nullable = false, unique = true)
  private String tokenHash;

  @Column(nullable = false)
  private boolean consumed;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "invited_by")
  private UUID invitedBy;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected Invitation() {}

  public Invitation(
      UUID tenantId, String email, Role role, String tokenHash, Instant expiresAt, UUID invitedBy) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.email = email;
    this.role = role;
    this.tokenHash = tokenHash;
    this.consumed = false;
    this.expiresAt = expiresAt;
    this.invitedBy = invitedBy;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void consume() {
    if (consumed) {
      throw new IllegalStateException("Invitation has already been consumed.");
    }
    if (isExpired()) {
      throw new IllegalStateException("Invitation has expired.");
    }
    this.consumed = true;
    this.updatedAt = Instant.now();
  }

  public boolean isExpired() {
    return expiresAt.isBefore(Instant.now());
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

  public Role getRole() {
    return role;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public boolean isConsumed() {
    return consumed;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public UUID getInvitedBy() {
    return invitedBy;
  }
}
