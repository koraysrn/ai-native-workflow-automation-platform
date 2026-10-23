package com.awap.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** Exactly-once record of a node's execution; guarded by a unique instance+node constraint. */
@Entity
@Table(name = "node_executions")
public class NodeExecution {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_instance_id", nullable = false)
  private UUID workflowInstanceId;

  @Column(name = "node_id", nullable = false)
  private String nodeId;

  @Column(name = "node_type", nullable = false)
  private String nodeType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private NodeExecutionStatus status;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @Column(columnDefinition = "TEXT")
  private String error;

  @Column(name = "resume_at")
  private Instant resumeAt;

  @Column(name = "branch_token_count")
  private Integer branchTokenCount;

  @Column(name = "merged_branch_count")
  private Integer mergedBranchCount;

  @Column(name = "join_node_id")
  private String joinNodeId;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  @LastModifiedBy
  @Column(name = "updated_by")
  private UUID updatedBy;

  protected NodeExecution() {}

  public NodeExecution(UUID tenantId, UUID workflowInstanceId, String nodeId, String nodeType) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowInstanceId = workflowInstanceId;
    this.nodeId = nodeId;
    this.nodeType = nodeType;
    this.status = NodeExecutionStatus.PENDING;
    this.attempt = 0;
    this.createdAt = Instant.now();
  }

  public void start() {
    this.status = NodeExecutionStatus.RUNNING;
    this.attempt = this.attempt + 1;
  }

  public void waitForExternal() {
    this.status = NodeExecutionStatus.WAITING;
  }

  public void complete(String outputJson) {
    this.status = NodeExecutionStatus.COMPLETED;
    this.outputJson = outputJson;
    this.completedAt = Instant.now();
  }

  /** Schedules a delayed node to be resumed at the given UTC instant. */
  public void scheduleResume(Instant resumeAt) {
    this.resumeAt = resumeAt;
  }

  public Instant getResumeAt() {
    return resumeAt;
  }

  /** Clears the resume instant once a delayed node has actually been resumed. */
  public void clearResumeAt() {
    this.resumeAt = null;
  }

  /** Opens the given number of parallel branches and resets the merge counter. */
  public void openBranches(int tokenCount) {
    this.branchTokenCount = tokenCount;
    this.mergedBranchCount = 0;
  }

  public int getBranchTokenCount() {
    return branchTokenCount == null ? 0 : branchTokenCount;
  }

  public int getMergedBranchCount() {
    return mergedBranchCount == null ? 0 : mergedBranchCount;
  }

  public void recordBranchMerge() {
    this.mergedBranchCount = getMergedBranchCount() + 1;
  }

  public String getJoinNodeId() {
    return joinNodeId;
  }

  public void setJoinNodeId(String joinNodeId) {
    this.joinNodeId = joinNodeId;
  }

  public void fail(String error) {
    this.status = NodeExecutionStatus.FAILED;
    this.error = error;
    this.completedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getWorkflowInstanceId() {
    return workflowInstanceId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getNodeType() {
    return nodeType;
  }

  public NodeExecutionStatus getStatus() {
    return status;
  }

  public int getAttempt() {
    return attempt;
  }

  public String getOutputJson() {
    return outputJson;
  }

  public String getError() {
    return error;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
