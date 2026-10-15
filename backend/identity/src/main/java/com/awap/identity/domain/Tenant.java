package com.awap.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tenant represents an isolated customer organization with a plan and usage quotas. */
@Entity
@Table(name = "tenants")
public class Tenant {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String slug;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String plan;

  @Column(name = "max_users", nullable = false)
  private int maxUsers;

  @Column(name = "max_workflows", nullable = false)
  private int maxWorkflows;

  @Column(name = "max_ai_calls_per_month", nullable = false)
  private long maxAiCallsPerMonth;

  @Column(nullable = false)
  private boolean active;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Column(nullable = false)
  private boolean deleted;

  protected Tenant() {}

  public Tenant(
      String slug,
      String name,
      String plan,
      int maxUsers,
      int maxWorkflows,
      long maxAiCallsPerMonth) {
    this.id = UUID.randomUUID();
    this.slug = slug;
    this.name = name;
    this.plan = plan;
    this.maxUsers = maxUsers;
    this.maxWorkflows = maxWorkflows;
    this.maxAiCallsPerMonth = maxAiCallsPerMonth;
    this.active = true;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
    this.deleted = false;
  }

  public void updateLimits(String plan, int maxUsers, int maxWorkflows, long maxAiCallsPerMonth) {
    this.plan = plan;
    this.maxUsers = maxUsers;
    this.maxWorkflows = maxWorkflows;
    this.maxAiCallsPerMonth = maxAiCallsPerMonth;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public String getPlan() {
    return plan;
  }

  public int getMaxUsers() {
    return maxUsers;
  }

  public int getMaxWorkflows() {
    return maxWorkflows;
  }

  public long getMaxAiCallsPerMonth() {
    return maxAiCallsPerMonth;
  }

  public boolean isActive() {
    return active;
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
