package com.awap.identity.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.domain.Tenant;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.organization.infrastructure.OrganizationUserRepository;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Enforces tenant-level quotas for workflows and organization users. */
@Service
public class QuotaService {

  private final TenantRepository tenantRepository;
  private final WorkflowDefinitionRepository workflowDefinitionRepository;
  private final OrganizationUserRepository organizationUserRepository;

  public QuotaService(
      TenantRepository tenantRepository,
      WorkflowDefinitionRepository workflowDefinitionRepository,
      OrganizationUserRepository organizationUserRepository) {
    this.tenantRepository = tenantRepository;
    this.workflowDefinitionRepository = workflowDefinitionRepository;
    this.organizationUserRepository = organizationUserRepository;
  }

  public void assertCanCreateWorkflow(UUID tenantId) {
    Tenant tenant = tenant(tenantId);
    long current = workflowDefinitionRepository.countByTenantIdAndDeletedFalse(tenantId);
    if (current >= tenant.getMaxWorkflows()) {
      throw quotaExceeded("Workflow quota exceeded.");
    }
  }

  public void assertCanCreateUser(UUID tenantId) {
    Tenant tenant = tenant(tenantId);
    long current = organizationUserRepository.countByTenantIdAndDeletedFalse(tenantId);
    if (current >= tenant.getMaxUsers()) {
      throw quotaExceeded("User quota exceeded.");
    }
  }

  private Tenant tenant(UUID tenantId) {
    return tenantRepository
        .findById(tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.TENANT_NOT_FOUND, HttpStatus.NOT_FOUND, "The tenant was not found."));
  }

  private AwapException quotaExceeded(String detail) {
    return new AwapException(ErrorCode.QUOTA_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS, detail);
  }
}
