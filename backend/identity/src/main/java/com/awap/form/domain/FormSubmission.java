package com.awap.form.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tenant-scoped response submitted against a form definition. */
@Entity
@Table(name = "form_submissions")
public class FormSubmission {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "form_id", nullable = false)
  private UUID formId;

  @Column(name = "workflow_instance_id")
  private UUID workflowInstanceId;

  @Column(name = "data_json", nullable = false, columnDefinition = "TEXT")
  private String dataJson;

  @Column(name = "submitted_by", nullable = false)
  private UUID submittedBy;

  @Column(nullable = false)
  private Instant createdAt;

  protected FormSubmission() {}

  public FormSubmission(
      UUID tenantId, UUID formId, UUID workflowInstanceId, String dataJson, UUID submittedBy) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.formId = formId;
    this.workflowInstanceId = workflowInstanceId;
    this.dataJson = dataJson;
    this.submittedBy = submittedBy;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getFormId() {
    return formId;
  }

  public UUID getWorkflowInstanceId() {
    return workflowInstanceId;
  }

  public String getDataJson() {
    return dataJson;
  }

  public UUID getSubmittedBy() {
    return submittedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
