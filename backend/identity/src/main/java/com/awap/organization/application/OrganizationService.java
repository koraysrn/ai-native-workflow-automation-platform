package com.awap.organization.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.application.QuotaService;
import com.awap.identity.domain.Role;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.organization.domain.Company;
import com.awap.organization.domain.Department;
import com.awap.organization.domain.OrganizationAuditEvent;
import com.awap.organization.domain.OrganizationUser;
import com.awap.organization.domain.Position;
import com.awap.organization.domain.Team;
import com.awap.organization.infrastructure.CompanyRepository;
import com.awap.organization.infrastructure.DepartmentRepository;
import com.awap.organization.infrastructure.OrganizationAuditEventRepository;
import com.awap.organization.infrastructure.OrganizationUserRepository;
import com.awap.organization.infrastructure.PositionRepository;
import com.awap.organization.infrastructure.TeamRepository;
import com.awap.organization.interfaces.dto.CompanyRequest;
import com.awap.organization.interfaces.dto.CompanyResponse;
import com.awap.organization.interfaces.dto.CompanyTreeResponse;
import com.awap.organization.interfaces.dto.DepartmentRequest;
import com.awap.organization.interfaces.dto.DepartmentResponse;
import com.awap.organization.interfaces.dto.DepartmentTreeResponse;
import com.awap.organization.interfaces.dto.OrgUserRequest;
import com.awap.organization.interfaces.dto.OrgUserResponse;
import com.awap.organization.interfaces.dto.OrganizationMapper;
import com.awap.organization.interfaces.dto.PositionRequest;
import com.awap.organization.interfaces.dto.PositionResponse;
import com.awap.organization.interfaces.dto.PositionRoleRequest;
import com.awap.organization.interfaces.dto.TeamRequest;
import com.awap.organization.interfaces.dto.TeamResponse;
import com.awap.organization.interfaces.dto.TeamTreeResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organization hierarchy use cases: Company, Department, Team, Position and User CRUD. */
@Service
public class OrganizationService {

  private final CompanyRepository companyRepository;
  private final DepartmentRepository departmentRepository;
  private final TeamRepository teamRepository;
  private final PositionRepository positionRepository;
  private final OrganizationUserRepository organizationUserRepository;
  private final QuotaService quotaService;
  private final OutboxEventPublisher outboxEventPublisher;
  private final OrganizationMapper organizationMapper;
  private final UserRepository userRepository;
  private final OrganizationAuditEventRepository auditEventRepository;

  public OrganizationService(
      CompanyRepository companyRepository,
      DepartmentRepository departmentRepository,
      TeamRepository teamRepository,
      PositionRepository positionRepository,
      OrganizationUserRepository organizationUserRepository,
      QuotaService quotaService,
      OutboxEventPublisher outboxEventPublisher,
      OrganizationMapper organizationMapper,
      UserRepository userRepository,
      OrganizationAuditEventRepository auditEventRepository) {
    this.companyRepository = companyRepository;
    this.departmentRepository = departmentRepository;
    this.teamRepository = teamRepository;
    this.positionRepository = positionRepository;
    this.organizationUserRepository = organizationUserRepository;
    this.quotaService = quotaService;
    this.outboxEventPublisher = outboxEventPublisher;
    this.organizationMapper = organizationMapper;
    this.userRepository = userRepository;
    this.auditEventRepository = auditEventRepository;
  }

  @Transactional
  public CompanyResponse createCompany(UUID tenantId, CompanyRequest request) {
    Company company = new Company(tenantId, request.name());
    companyRepository.save(company);
    publish(
        tenantId,
        "organization.company.created",
        company.getId(),
        Map.of("tenantId", tenantId, "entityId", company.getId(), "name", company.getName()));
    return organizationMapper.toResponse(company);
  }

  public List<CompanyResponse> listCompanies(UUID tenantId) {
    return companyRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(organizationMapper::toResponse)
        .toList();
  }

  @Transactional
  public DepartmentResponse createDepartment(UUID tenantId, DepartmentRequest request) {
    companyRepository
        .findByIdAndTenantIdAndDeletedFalse(request.companyId(), tenantId)
        .orElseThrow(() -> notFound("Company not found."));
    Department department =
        new Department(tenantId, request.companyId(), request.parentId(), request.name());
    departmentRepository.save(department);
    publish(
        tenantId,
        "organization.department.created",
        department.getId(),
        Map.of("tenantId", tenantId, "entityId", department.getId(), "name", department.getName()));
    return organizationMapper.toResponse(department);
  }

  public List<DepartmentResponse> listDepartments(UUID tenantId, UUID companyId) {
    return departmentRepository
        .findByTenantIdAndCompanyIdAndDeletedFalse(tenantId, companyId)
        .stream()
        .map(organizationMapper::toResponse)
        .toList();
  }

  @Transactional
  public TeamResponse createTeam(UUID tenantId, TeamRequest request) {
    departmentRepository
        .findByIdAndTenantIdAndDeletedFalse(request.departmentId(), tenantId)
        .orElseThrow(() -> notFound("Department not found."));
    Team team = new Team(tenantId, request.departmentId(), request.name());
    teamRepository.save(team);
    publish(
        tenantId,
        "organization.team.created",
        team.getId(),
        Map.of("tenantId", tenantId, "entityId", team.getId(), "name", team.getName()));
    return organizationMapper.toResponse(team);
  }

  public List<TeamResponse> listTeams(UUID tenantId, UUID departmentId) {
    return teamRepository
        .findByTenantIdAndDepartmentIdAndDeletedFalse(tenantId, departmentId)
        .stream()
        .map(organizationMapper::toResponse)
        .toList();
  }

  @Transactional
  public PositionResponse createPosition(
      UUID tenantId, PositionRequest request, String callerRole) {
    Role role = request.role() == null ? Role.EMPLOYEE : request.role();
    assertCanAssign(callerRole, role);
    Position position = new Position(tenantId, request.name(), role);
    positionRepository.save(position);
    publish(
        tenantId,
        "organization.position.created",
        position.getId(),
        Map.of(
            "tenantId", tenantId,
            "entityId", position.getId(),
            "name", position.getName(),
            "role", position.getRole().name()));
    return organizationMapper.toResponse(position);
  }

  /**
   * Changes the role attached to a position and recalculates the effective authorities of every
   * user currently placed in it (issue #194). The change is audited and published to the outbox.
   */
  @Transactional
  public PositionResponse updatePositionRole(
      UUID tenantId,
      UUID positionId,
      PositionRoleRequest request,
      String callerRole,
      UUID actorId) {
    Position position =
        positionRepository
            .findByIdAndTenantIdAndDeletedFalse(positionId, tenantId)
            .orElseThrow(() -> notFound("Position not found."));
    Role target = request.role();
    assertCanAssign(callerRole, target);
    Role previous = position.getRole();
    if (previous == target) {
      return organizationMapper.toResponse(position);
    }
    position.changeRole(target);
    List<UUID> affectedUsers = propagateRole(tenantId, positionId, target);
    auditEventRepository.save(
        new OrganizationAuditEvent(
            tenantId,
            "POSITION",
            positionId,
            "ROLE_CHANGED",
            previous.name(),
            target.name(),
            actorId));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", tenantId);
    payload.put("entityId", positionId);
    payload.put("oldRole", previous.name());
    payload.put("newRole", target.name());
    payload.put("affectedUserIds", affectedUsers);
    publish(tenantId, "organization.position.role_changed", positionId, payload);
    return organizationMapper.toResponse(position);
  }

  private List<UUID> propagateRole(UUID tenantId, UUID positionId, Role role) {
    List<UUID> affected = new ArrayList<>();
    for (OrganizationUser member :
        organizationUserRepository.findByTenantIdAndPositionIdAndDeletedFalse(
            tenantId, positionId)) {
      userRepository
          .findByTenantIdAndEmailRls(tenantId, member.getEmail())
          .ifPresent(
              user -> {
                user.assignRole(role);
                userRepository.save(user);
                affected.add(user.getId());
              });
    }
    return affected;
  }

  private void assertCanAssign(String callerRole, Role target) {
    if (target == null) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "A position role is required.");
    }
    if (callerRole == null || callerRole.isBlank()) {
      return;
    }
    Role caller;
    try {
      caller = Role.valueOf(callerRole);
    } catch (IllegalArgumentException ex) {
      throw new AwapException(
          ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "Unknown caller role for role assignment.");
    }
    if (!caller.canAssign(target)) {
      throw new AwapException(
          ErrorCode.FORBIDDEN,
          HttpStatus.FORBIDDEN,
          "Cannot assign a role above the caller's authority.");
    }
  }

  public List<PositionResponse> listPositions(UUID tenantId) {
    return positionRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(organizationMapper::toResponse)
        .toList();
  }

  @Transactional
  public OrgUserResponse createUser(UUID tenantId, OrgUserRequest request) {
    quotaService.assertCanCreateUser(tenantId);
    Position position = null;
    if (request.positionId() != null) {
      position =
          positionRepository
              .findByIdAndTenantIdAndDeletedFalse(request.positionId(), tenantId)
              .orElseThrow(() -> notFound("Position not found."));
    }
    OrganizationUser user =
        new OrganizationUser(
            tenantId, request.email(), request.fullName(), request.teamId(), request.positionId());
    organizationUserRepository.save(user);
    if (position != null) {
      applyPositionRole(tenantId, request.email(), position);
    }
    publish(
        tenantId,
        "organization.user.created",
        user.getId(),
        Map.of("tenantId", tenantId, "entityId", user.getId(), "email", user.getEmail()));
    return organizationMapper.toResponse(user);
  }

  public List<OrgUserResponse> listUsers(UUID tenantId) {
    return organizationUserRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(organizationMapper::toResponse)
        .toList();
  }

  public List<CompanyTreeResponse> getTree(UUID tenantId) {
    List<Department> departments = departmentRepository.findByTenantIdAndDeletedFalse(tenantId);
    List<Team> teams = teamRepository.findByTenantIdAndDeletedFalse(tenantId);
    List<OrganizationUser> users =
        organizationUserRepository.findByTenantIdAndDeletedFalse(tenantId);

    Map<UUID, List<Department>> departmentsByCompany =
        departments.stream().collect(Collectors.groupingBy(Department::getCompanyId));
    Map<UUID, List<Team>> teamsByDepartment =
        teams.stream().collect(Collectors.groupingBy(Team::getDepartmentId));
    Map<UUID, List<OrganizationUser>> usersByTeam =
        users.stream()
            .filter(user -> user.getTeamId() != null)
            .collect(Collectors.groupingBy(OrganizationUser::getTeamId));

    return companyRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(
            company ->
                new CompanyTreeResponse(
                    company.getId(),
                    company.getName(),
                    departmentsByCompany.getOrDefault(company.getId(), List.of()).stream()
                        .map(
                            department ->
                                new DepartmentTreeResponse(
                                    department.getId(),
                                    department.getName(),
                                    teamsByDepartment
                                        .getOrDefault(department.getId(), List.of())
                                        .stream()
                                        .map(
                                            team ->
                                                new TeamTreeResponse(
                                                    team.getId(),
                                                    team.getName(),
                                                    usersByTeam
                                                        .getOrDefault(team.getId(), List.of())
                                                        .stream()
                                                        .map(organizationMapper::toResponse)
                                                        .toList()))
                                        .toList()))
                        .toList()))
        .toList();
  }

  @Transactional
  public OrgUserResponse assignToTeam(UUID tenantId, UUID userId, UUID teamId) {
    teamRepository
        .findByIdAndTenantIdAndDeletedFalse(teamId, tenantId)
        .orElseThrow(() -> notFound("Team not found."));
    OrganizationUser user =
        organizationUserRepository
            .findByIdAndTenantIdAndDeletedFalse(userId, tenantId)
            .orElseThrow(() -> notFound("User not found."));
    user.assignToTeam(teamId);
    publish(
        tenantId,
        "organization.team.user_assigned",
        user.getId(),
        Map.of("tenantId", tenantId, "entityId", user.getId(), "teamId", teamId));
    return organizationMapper.toResponse(user);
  }

  private void applyPositionRole(UUID tenantId, String email, Position position) {
    userRepository
        .findByTenantIdAndEmailRls(tenantId, email)
        .ifPresent(
            user -> {
              user.assignRole(position.getRole());
              userRepository.save(user);
            });
  }

  private void publish(UUID tenantId, String eventType, UUID entityId, Object snapshot) {
    outboxEventPublisher.publish(tenantId, "ORGANIZATION", entityId, eventType, snapshot);
  }

  private AwapException notFound(String detail) {
    return new AwapException(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, detail);
  }
}
