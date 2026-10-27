package com.awap.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable audit row for an AI decision/tool call or a SCRIPT sandbox execution. */
@Entity
@Table(name = "automation_audit_records")
public class AutomationAudit {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "instance_id")
  private UUID instanceId;

  @Column(name = "node_id", nullable = false)
  private String nodeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AuditKind kind;

  @Column(nullable = false)
  private String action;

  private String provider;

  private String model;

  @Column(name = "prompt_hash")
  private String promptHash;

  private BigDecimal confidence;

  @Column(nullable = false)
  private int tokens;

  @Column(name = "cost_micros", nullable = false)
  private long costMicros;

  @Column(nullable = false)
  private String outcome;

  @Column(columnDefinition = "TEXT")
  private String detail;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AutomationAudit() {}

  public AutomationAudit(
      UUID tenantId,
      UUID instanceId,
      String nodeId,
      AuditKind kind,
      String action,
      String provider,
      String model,
      String promptHash,
      BigDecimal confidence,
      int tokens,
      long costMicros,
      String outcome,
      String detail) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.instanceId = instanceId;
    this.nodeId = nodeId;
    this.kind = kind;
    this.action = action;
    this.provider = provider;
    this.model = model;
    this.promptHash = promptHash;
    this.confidence = confidence;
    this.tokens = Math.max(0, tokens);
    this.costMicros = Math.max(0, costMicros);
    this.outcome = outcome;
    this.detail = detail;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getInstanceId() {
    return instanceId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public AuditKind getKind() {
    return kind;
  }

  public String getAction() {
    return action;
  }

  public String getProvider() {
    return provider;
  }

  public String getModel() {
    return model;
  }

  public String getPromptHash() {
    return promptHash;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public int getTokens() {
    return tokens;
  }

  public long getCostMicros() {
    return costMicros;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Audit category. */
  public enum AuditKind {
    AI,
    SCRIPT
  }
}
