package com.awap.identity.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.Tenant;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.identity.interfaces.dto.LimitsRequest;
import com.awap.identity.interfaces.dto.TenantRequest;
import com.awap.identity.interfaces.dto.TenantResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant on-boarding and quota management use cases. */
@Service
public class TenantService {

  private final TenantRepository tenantRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public TenantService(
      TenantRepository tenantRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder) {
    this.tenantRepository = tenantRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public TenantResponse createTenant(TenantRequest request) {
    if (tenantRepository.existsBySlug(request.slug())) {
      throw new AwapException(
          ErrorCode.TENANT_ALREADY_EXISTS,
          HttpStatus.CONFLICT,
          "A tenant with this slug already exists.");
    }
    Tenant tenant = new Tenant(request.slug(), request.name(), "FREE", 100, 50, 1000);
    tenantRepository.save(tenant);
    User admin =
        new User(
            tenant.getId(),
            request.adminEmail(),
            passwordEncoder.encode(request.adminPassword()),
            request.adminFullName(),
            Role.TENANT_ADMIN);
    userRepository.save(admin);
    return toResponse(tenant);
  }

  public TenantResponse getTenant(UUID id, UUID callerTenantId, String callerRole) {
    requireAccess(id, callerTenantId, callerRole);
    return toResponse(findTenant(id));
  }

  @Transactional
  public TenantResponse updateLimits(
      UUID id, LimitsRequest request, UUID callerTenantId, String callerRole) {
    requireAccess(id, callerTenantId, callerRole);
    boolean crossTenantAdmin = "SYSTEM_ADMIN".equals(callerRole) && !id.equals(callerTenantId);
    if (crossTenantAdmin) {
      tenantRepository.updateLimitsRls(
          id,
          request.plan(),
          request.maxUsers(),
          request.maxWorkflows(),
          request.maxAiCallsPerMonth());
      // Re-read after the RLS-bypassing native update so the response reflects
      // the new limits instead of the stale pre-update entity.
      return toResponse(findTenantRls(id));
    }
    Tenant tenant = findTenant(id);
    tenant.updateLimits(
        request.plan(), request.maxUsers(), request.maxWorkflows(), request.maxAiCallsPerMonth());
    return toResponse(tenant);
  }

  private Tenant findTenant(UUID id) {
    return tenantRepository
        .findById(id)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.TENANT_NOT_FOUND, HttpStatus.NOT_FOUND, "The tenant was not found."));
  }

  private Tenant findTenantRls(UUID id) {
    return tenantRepository
        .findByIdRls(id)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.TENANT_NOT_FOUND, HttpStatus.NOT_FOUND, "The tenant was not found."));
  }

  private void requireAccess(UUID id, UUID callerTenantId, String callerRole) {
    if (!id.equals(callerTenantId) && !"SYSTEM_ADMIN".equals(callerRole)) {
      throw new AwapException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "Access denied.");
    }
  }

  private TenantResponse toResponse(Tenant tenant) {
    return new TenantResponse(
        tenant.getId(),
        tenant.getSlug(),
        tenant.getName(),
        tenant.getPlan(),
        tenant.getMaxUsers(),
        tenant.getMaxWorkflows(),
        tenant.getMaxAiCallsPerMonth(),
        tenant.isActive());
  }
}
