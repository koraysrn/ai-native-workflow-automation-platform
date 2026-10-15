package com.awap.form.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tenant-scoped form schema. */
@Entity
@Table(name = "form_definitions")
public class FormDefinition {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String name;

  @Column(name = "schema_json", nullable = false, columnDefinition = "TEXT")
  private String schemaJson;

  @Column(nullable = false)
  private int version;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Column(nullable = false)
  private boolean deleted;

  protected FormDefinition() {}

  public FormDefinition(UUID tenantId, String name, String schemaJson) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.name = name;
    this.schemaJson = schemaJson;
    this.version = 1;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
    this.deleted = false;
  }

  public void updateSchema(String name, String schemaJson) {
    this.name = name;
    this.schemaJson = schemaJson;
    this.version++;
    this.updatedAt = Instant.now();
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

  public String getSchemaJson() {
    return schemaJson;
  }

  public int getVersion() {
    return version;
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
