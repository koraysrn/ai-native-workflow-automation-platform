package com.awap.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;

/** Stores an idempotency key for a tenant to prevent duplicate mutating requests. */
@Entity
@Table(
    name = "idempotency_records",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_idempotency_records",
            columnNames = {"tenant_id", "idempotency_key"}))
public class IdempotencyRecord {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "response_status", nullable = false)
  private int responseStatus;

  @Column(name = "response_body", columnDefinition = "TEXT")
  private String responseBody;

  @Column(name = "response_content_type")
  private String responseContentType;

  @Column(nullable = false)
  private Instant createdAt;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  protected IdempotencyRecord() {}

  public IdempotencyRecord(UUID tenantId, String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.idempotencyKey = idempotencyKey;
    this.responseStatus = 0;
    this.createdAt = Instant.now();
  }

  public void setResponse(int status, String body, String contentType) {
    this.responseStatus = status;
    this.responseBody = body;
    this.responseContentType = contentType;
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

  public int getResponseStatus() {
    return responseStatus;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public String getResponseContentType() {
    return responseContentType;
  }
}
