package com.awap.identity.domain;

import java.util.Set;

/** Roles supported by the AWAP platform, each granting a set of permissions. */
public enum Role {
  SYSTEM_ADMIN(
      Set.of(
          Permission.TENANT_MANAGE,
          Permission.USER_MANAGE,
          Permission.WORKFLOW_CREATE,
          Permission.WORKFLOW_PUBLISH,
          Permission.INSTANCE_READ,
          Permission.INSTANCE_MANAGE,
          Permission.TASK_APPROVE,
          Permission.TASK_MANAGE,
          Permission.FORM_MANAGE,
          Permission.FILE_UPLOAD)),
  TENANT_ADMIN(
      Set.of(
          Permission.USER_MANAGE,
          Permission.WORKFLOW_CREATE,
          Permission.WORKFLOW_PUBLISH,
          Permission.INSTANCE_READ,
          Permission.INSTANCE_MANAGE,
          Permission.TASK_APPROVE,
          Permission.TASK_MANAGE,
          Permission.FORM_MANAGE,
          Permission.FILE_UPLOAD)),
  MANAGER(
      Set.of(
          Permission.WORKFLOW_CREATE,
          Permission.INSTANCE_READ,
          Permission.TASK_APPROVE,
          Permission.TASK_MANAGE)),
  OPERATIONS(
      Set.of(
          Permission.INSTANCE_READ,
          Permission.TASK_MANAGE,
          Permission.FORM_MANAGE,
          Permission.FILE_UPLOAD)),
  EMPLOYEE(Set.of(Permission.INSTANCE_READ, Permission.TASK_MANAGE, Permission.FORM_MANAGE));

  private final Set<Permission> permissions;

  Role(Set<Permission> permissions) {
    this.permissions = permissions;
  }

  public Set<Permission> permissions() {
    return permissions;
  }

  public boolean hasPermission(Permission permission) {
    return permissions.contains(permission);
  }
}
