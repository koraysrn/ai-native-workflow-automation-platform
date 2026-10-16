package com.awap.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** A typed workflow variable declared on a workflow version. */
@Entity
@Table(name = "workflow_variables")
public class WorkflowVariable {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(nullable = false)
  private String name;

  @Column(name = "data_type", nullable = false)
  private String dataType;

  @Column(name = "default_value")
  private String defaultValue;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  @LastModifiedBy
  @Column(name = "updated_by")
  private UUID updatedBy;

  protected WorkflowVariable() {}

  public WorkflowVariable(
      UUID tenantId, UUID workflowVersionId, String name, String dataType, String defaultValue) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowVersionId = workflowVersionId;
    this.name = name;
    this.dataType = dataType;
    this.defaultValue = defaultValue;
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

  public String getName() {
    return name;
  }

  public String getDataType() {
    return dataType;
  }

  public String getDefaultValue() {
    return defaultValue;
  }
}
