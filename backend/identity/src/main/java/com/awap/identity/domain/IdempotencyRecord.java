package com.awap.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Stores an idempotency key for a tenant to prevent duplicate mutating requests. */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(nullable = false)
  private Instant createdAt;

  protected IdempotencyRecord() {}

  public IdempotencyRecord(UUID tenantId, String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.idempotencyKey = idempotencyKey;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
