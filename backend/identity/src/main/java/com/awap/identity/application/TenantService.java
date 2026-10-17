package com.awap.identity.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.domain.Invitation;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.Tenant;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.InvitationRepository;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.identity.interfaces.dto.AcceptInvitationRequest;
import com.awap.identity.interfaces.dto.InvitationResponse;
import com.awap.identity.interfaces.dto.InviteUserRequest;
import com.awap.identity.interfaces.dto.LimitsRequest;
import com.awap.identity.interfaces.dto.TenantRequest;
import com.awap.identity.interfaces.dto.TenantResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
  private final InvitationRepository invitationRepository;

  public TenantService(
      TenantRepository tenantRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      InvitationRepository invitationRepository) {
    this.tenantRepository = tenantRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.invitationRepository = invitationRepository;
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
    Tenant tenant = findTenant(id);
    tenant.updateLimits(
        request.plan(), request.maxUsers(), request.maxWorkflows(), request.maxAiCallsPerMonth());
    return toResponse(tenant);
  }

  @Transactional
  public InvitationResponse inviteUser(
      UUID id, InviteUserRequest request, UUID callerTenantId, String callerRole, UUID actorId) {
    requireTenantAdmin(callerRole);
    requireAccess(id, callerTenantId, callerRole);
    Tenant tenant = findTenant(id);
    String rawToken = UUID.randomUUID().toString();
    Instant expiresAt = Instant.now().plus(Duration.ofDays(7));
    Invitation invitation =
        new Invitation(
            tenant.getId(),
            request.email(),
            parseRole(request.role()),
            hashToken(rawToken),
            expiresAt,
            actorId);
    invitationRepository.save(invitation);
    return new InvitationResponse(
        invitation.getId(),
        invitation.getTenantId(),
        invitation.getEmail(),
        invitation.getRole().name(),
        invitation.getExpiresAt(),
        rawToken);
  }

  @Transactional
  public TenantResponse acceptInvitation(String token, AcceptInvitationRequest request) {
    Invitation invitation =
        invitationRepository
            .findByTokenHash(hashToken(token))
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.VALIDATION_FAILED,
                        HttpStatus.BAD_REQUEST,
                        "Invalid or unknown invitation token."));
    try {
      invitation.consume();
    } catch (IllegalStateException ex) {
      throw new AwapException(ErrorCode.VALIDATION_FAILED, HttpStatus.CONFLICT, ex.getMessage());
    }
    Tenant tenant = findTenantRls(invitation.getTenantId());
    long userCount = userRepository.countByTenantIdAndDeletedFalseRls(tenant.getId());
    if (userCount >= tenant.getMaxUsers()) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.CONFLICT, "Tenant user quota exceeded.");
    }
    User user =
        userRepository
            .findByTenantIdAndEmailRls(tenant.getId(), invitation.getEmail())
            .map(
                existing -> {
                  existing.assignRole(invitation.getRole());
                  return existing;
                })
            .orElseGet(
                () ->
                    new User(
                        tenant.getId(),
                        invitation.getEmail(),
                        passwordEncoder.encode(requirePassword(request)),
                        request.fullName() == null ? invitation.getEmail() : request.fullName(),
                        invitation.getRole()));
    userRepository.save(user);
    return toResponse(tenant);
  }

  private String requirePassword(AcceptInvitationRequest request) {
    if (request.password() == null || request.password().isBlank()) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.BAD_REQUEST,
          "Password is required to accept an invitation as a new user.");
    }
    return request.password();
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

  private Role parseRole(String role) {
    try {
      return Role.valueOf(role);
    } catch (IllegalArgumentException ex) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "Unknown role: " + role);
    }
  }

  private void requireAccess(UUID id, UUID callerTenantId, String callerRole) {
    if (!id.equals(callerTenantId) && !"SYSTEM_ADMIN".equals(callerRole)) {
      throw new AwapException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "Access denied.");
    }
  }

  private void requireTenantAdmin(String callerRole) {
    if (!"TENANT_ADMIN".equals(callerRole) && !"SYSTEM_ADMIN".equals(callerRole)) {
      throw new AwapException(
          ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "Only tenant admins can invite users.");
    }
  }

  private String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
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
