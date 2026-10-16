package com.awap.identity.domain;

/** Fine-grained permissions granted through roles (RBAC). */
public enum Permission {
  TENANT_MANAGE,
  USER_MANAGE,
  WORKFLOW_CREATE,
  WORKFLOW_PUBLISH,
  INSTANCE_READ,
  INSTANCE_MANAGE,
  TASK_APPROVE,
  TASK_MANAGE,
  FORM_MANAGE,
  FILE_UPLOAD
}
