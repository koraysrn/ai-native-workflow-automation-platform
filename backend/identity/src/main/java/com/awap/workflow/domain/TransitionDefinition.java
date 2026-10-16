package com.awap.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** A directed edge between two nodes, optionally guarded by a SpEL expression. */
@Entity
@Table(name = "workflow_transitions")
public class TransitionDefinition {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(name = "from_node_id", nullable = false)
  private String fromNodeId;

  @Column(name = "to_node_id", nullable = false)
  private String toNodeId;

  @Column(name = "condition_expression")
  private String conditionExpression;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  @LastModifiedBy
  @Column(name = "updated_by")
  private UUID updatedBy;

  protected TransitionDefinition() {}

  public TransitionDefinition(
      UUID tenantId,
      UUID workflowVersionId,
      String fromNodeId,
      String toNodeId,
      String conditionExpression) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowVersionId = workflowVersionId;
    this.fromNodeId = fromNodeId;
    this.toNodeId = toNodeId;
    this.conditionExpression = conditionExpression;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getWorkflowVersionId() {
    return workflowVersionId;
  }

  public String getFromNodeId() {
    return fromNodeId;
  }

  public String getToNodeId() {
    return toNodeId;
  }

  public String getConditionExpression() {
    return conditionExpression;
  }
}
