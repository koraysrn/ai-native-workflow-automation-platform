package com.awap.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A single node in a workflow version's graph. */
@Entity
@Table(name = "workflow_nodes")
public class NodeDefinition {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(name = "node_id", nullable = false)
  private String nodeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "node_type", nullable = false)
  private NodeType nodeType;

  @Column(nullable = false)
  private String name;

  @Column(name = "config_json", columnDefinition = "TEXT")
  private String configJson;

  @Column(name = "order_index", nullable = false)
  private int orderIndex;

  protected NodeDefinition() {}

  public NodeDefinition(
      UUID tenantId,
      UUID workflowVersionId,
      String nodeId,
      NodeType nodeType,
      String name,
      String configJson,
      int orderIndex) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowVersionId = workflowVersionId;
    this.nodeId = nodeId;
    this.nodeType = nodeType;
    this.name = name;
    this.configJson = configJson;
    this.orderIndex = orderIndex;
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

  public String getNodeId() {
    return nodeId;
  }

  public NodeType getNodeType() {
    return nodeType;
  }

  public String getName() {
    return name;
  }

  public String getConfigJson() {
    return configJson;
  }

  public int getOrderIndex() {
    return orderIndex;
  }
}
