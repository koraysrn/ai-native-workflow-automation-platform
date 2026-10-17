package com.awap.organization.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.application.QuotaService;
import com.awap.organization.domain.Company;
import com.awap.organization.domain.Department;
import com.awap.organization.domain.OrganizationUser;
import com.awap.organization.domain.Position;
import com.awap.organization.domain.Team;
import com.awap.organization.infrastructure.CompanyRepository;
import com.awap.organization.infrastructure.DepartmentRepository;
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
import com.awap.organization.interfaces.dto.PositionRequest;
import com.awap.organization.interfaces.dto.PositionResponse;
import com.awap.organization.interfaces.dto.TeamRequest;
import com.awap.organization.interfaces.dto.TeamResponse;
import com.awap.organization.interfaces.dto.TeamTreeResponse;
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

  public OrganizationService(
      CompanyRepository companyRepository,
      DepartmentRepository departmentRepository,
      TeamRepository teamRepository,
      PositionRepository positionRepository,
      OrganizationUserRepository organizationUserRepository,
      QuotaService quotaService,
      OutboxEventPublisher outboxEventPublisher) {
    this.companyRepository = companyRepository;
    this.departmentRepository = departmentRepository;
    this.teamRepository = teamRepository;
    this.positionRepository = positionRepository;
    this.organizationUserRepository = organizationUserRepository;
    this.quotaService = quotaService;
    this.outboxEventPublisher = outboxEventPublisher;
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
    return toResponse(company);
  }

  public List<CompanyResponse> listCompanies(UUID tenantId) {
    return companyRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(this::toResponse)
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
    return toResponse(department);
  }

  public List<DepartmentResponse> listDepartments(UUID tenantId, UUID companyId) {
    return departmentRepository
        .findByTenantIdAndCompanyIdAndDeletedFalse(tenantId, companyId)
        .stream()
        .map(this::toResponse)
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
    return toResponse(team);
  }

  public List<TeamResponse> listTeams(UUID tenantId, UUID departmentId) {
    return teamRepository
        .findByTenantIdAndDepartmentIdAndDeletedFalse(tenantId, departmentId)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public PositionResponse createPosition(UUID tenantId, PositionRequest request) {
    Position position = new Position(tenantId, request.name());
    positionRepository.save(position);
    publish(
        tenantId,
        "organization.position.created",
        position.getId(),
        Map.of("tenantId", tenantId, "entityId", position.getId(), "name", position.getName()));
    return toResponse(position);
  }

  public List<PositionResponse> listPositions(UUID tenantId) {
    return positionRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public OrgUserResponse createUser(UUID tenantId, OrgUserRequest request) {
    quotaService.assertCanCreateUser(tenantId);
    OrganizationUser user =
        new OrganizationUser(
            tenantId, request.email(), request.fullName(), request.teamId(), request.positionId());
    organizationUserRepository.save(user);
    publish(
        tenantId,
        "organization.user.created",
        user.getId(),
        Map.of("tenantId", tenantId, "entityId", user.getId(), "email", user.getEmail()));
    return toResponse(user);
  }

  public List<OrgUserResponse> listUsers(UUID tenantId) {
    return organizationUserRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(this::toResponse)
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
                                                        .map(this::toResponse)
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
    return toResponse(user);
  }

  private void publish(UUID tenantId, String eventType, UUID entityId, Object snapshot) {
    outboxEventPublisher.publish(tenantId, "ORGANIZATION", entityId, eventType, snapshot);
  }

  private AwapException notFound(String detail) {
    return new AwapException(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, detail);
  }

  private CompanyResponse toResponse(Company company) {
    return new CompanyResponse(company.getId(), company.getTenantId(), company.getName());
  }

  private DepartmentResponse toResponse(Department department) {
    return new DepartmentResponse(
        department.getId(),
        department.getTenantId(),
        department.getCompanyId(),
        department.getParentId(),
        department.getName());
  }

  private TeamResponse toResponse(Team team) {
    return new TeamResponse(
        team.getId(), team.getTenantId(), team.getDepartmentId(), team.getName());
  }

  private PositionResponse toResponse(Position position) {
    return new PositionResponse(position.getId(), position.getTenantId(), position.getName());
  }

  private OrgUserResponse toResponse(OrganizationUser user) {
    return new OrgUserResponse(
        user.getId(),
        user.getTenantId(),
        user.getEmail(),
        user.getFullName(),
        user.getTeamId(),
        user.getPositionId());
  }
}
