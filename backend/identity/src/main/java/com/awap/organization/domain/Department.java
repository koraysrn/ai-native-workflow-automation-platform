package com.awap.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** A department that belongs to a company and may nest under another department. */
@Entity
@Table(name = "departments")
public class Department {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "company_id", nullable = false)
  private UUID companyId;

  @Column(name = "parent_id")
  private UUID parentId;

  @Column(nullable = false)
  private String name;

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

  protected Department() {}

  public Department(UUID tenantId, UUID companyId, UUID parentId, String name) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.companyId = companyId;
    this.parentId = parentId;
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

  public UUID getCompanyId() {
    return companyId;
  }

  public UUID getParentId() {
    return parentId;
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
