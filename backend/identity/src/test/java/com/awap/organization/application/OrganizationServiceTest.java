package com.awap.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.application.QuotaService;
import com.awap.identity.domain.Role;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.organization.domain.Company;
import com.awap.organization.domain.Department;
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
import com.awap.organization.interfaces.dto.OrgUserRequest;
import com.awap.organization.interfaces.dto.OrgUserResponse;
import com.awap.organization.interfaces.dto.OrganizationMapperImpl;
import com.awap.organization.interfaces.dto.PositionRequest;
import com.awap.organization.interfaces.dto.PositionResponse;
import com.awap.organization.interfaces.dto.TeamRequest;
import com.awap.organization.interfaces.dto.TeamResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private DepartmentRepository departmentRepository;
  @Mock private TeamRepository teamRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private OrganizationUserRepository organizationUserRepository;
  @Mock private QuotaService quotaService;
  @Mock private OutboxEventPublisher outboxEventPublisher;
  @Mock private UserRepository userRepository;
  @Mock private OrganizationAuditEventRepository auditEventRepository;

  private OrganizationService service;

  @BeforeEach
  void setUp() {
    service =
        new OrganizationService(
            companyRepository,
            departmentRepository,
            teamRepository,
            positionRepository,
            organizationUserRepository,
            quotaService,
            outboxEventPublisher,
            new OrganizationMapperImpl(),
            userRepository,
            auditEventRepository);
  }

  @Test
  void createCompanyReturnsCompany() {
    CompanyResponse response = service.createCompany(UUID.randomUUID(), new CompanyRequest("Acme"));
    assertThat(response.name()).isEqualTo("Acme");
  }

  @Test
  void createCompanyPublishesOutboxEvent() {
    UUID tenantId = UUID.randomUUID();

    service.createCompany(tenantId, new CompanyRequest("Acme"));

    verify(outboxEventPublisher)
        .publish(
            eq(tenantId),
            eq("ORGANIZATION"),
            any(UUID.class),
            eq("organization.company.created"),
            any());
  }

  @Test
  void createDepartmentRejectsUnknownCompany() {
    UUID tenantId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();
    when(companyRepository.findByIdAndTenantIdAndDeletedFalse(companyId, tenantId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.createDepartment(tenantId, new DepartmentRequest(companyId, null, "IT")))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void createDepartmentPersistsWhenTheCompanyExists() {
    UUID tenantId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();
    when(companyRepository.findByIdAndTenantIdAndDeletedFalse(companyId, tenantId))
        .thenReturn(Optional.of(new Company(tenantId, "Acme")));

    DepartmentResponse response =
        service.createDepartment(tenantId, new DepartmentRequest(companyId, null, "IT"));

    assertThat(response.name()).isEqualTo("IT");
    assertThat(response.companyId()).isEqualTo(companyId);
  }

  @Test
  void listCompaniesMapsEveryEntity() {
    UUID tenantId = UUID.randomUUID();
    when(companyRepository.findByTenantIdAndDeletedFalse(tenantId))
        .thenReturn(List.of(new Company(tenantId, "Acme")));

    assertThat(service.listCompanies(tenantId)).extracting(CompanyResponse::name).contains("Acme");
  }

  @Test
  void listDepartmentsMapsEveryEntity() {
    UUID tenantId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();
    when(departmentRepository.findByTenantIdAndCompanyIdAndDeletedFalse(tenantId, companyId))
        .thenReturn(List.of(new Department(tenantId, companyId, null, "IT")));

    assertThat(service.listDepartments(tenantId, companyId))
        .extracting(DepartmentResponse::name)
        .contains("IT");
  }

  @Test
  void createTeamPersistsWhenTheDepartmentExists() {
    UUID tenantId = UUID.randomUUID();
    UUID departmentId = UUID.randomUUID();
    when(departmentRepository.findByIdAndTenantIdAndDeletedFalse(departmentId, tenantId))
        .thenReturn(Optional.of(new Department(tenantId, UUID.randomUUID(), null, "IT")));

    TeamResponse response = service.createTeam(tenantId, new TeamRequest(departmentId, "Core"));

    assertThat(response.name()).isEqualTo("Core");
    assertThat(response.departmentId()).isEqualTo(departmentId);
  }

  @Test
  void listTeamsMapsEveryEntity() {
    UUID tenantId = UUID.randomUUID();
    UUID departmentId = UUID.randomUUID();
    when(teamRepository.findByTenantIdAndDepartmentIdAndDeletedFalse(tenantId, departmentId))
        .thenReturn(List.of(new Team(tenantId, departmentId, "Core")));

    assertThat(service.listTeams(tenantId, departmentId))
        .extracting(TeamResponse::name)
        .contains("Core");
  }

  @Test
  void listPositionsMapsEveryEntity() {
    UUID tenantId = UUID.randomUUID();
    when(positionRepository.findByTenantIdAndDeletedFalse(tenantId))
        .thenReturn(List.of(new Position(tenantId, "Finance", Role.MANAGER)));

    List<PositionResponse> positions = service.listPositions(tenantId);

    assertThat(positions)
        .singleElement()
        .satisfies(p -> assertThat(p.role()).isEqualTo(Role.MANAGER));
  }

  @Test
  void listUsersMapsEveryEntity() {
    UUID tenantId = UUID.randomUUID();
    when(organizationUserRepository.findByTenantIdAndDeletedFalse(tenantId))
        .thenReturn(List.of(new OrganizationUser(tenantId, "a@acme.com", "A", null, null)));

    assertThat(service.listUsers(tenantId))
        .extracting(OrgUserResponse::email)
        .contains("a@acme.com");
  }

  @Test
  void getTreeNestsTeamsAndAssignedUsers() {
    UUID tenantId = UUID.randomUUID();
    Company company = new Company(tenantId, "Acme");
    Department department = new Department(tenantId, company.getId(), null, "IT");
    Team team = new Team(tenantId, department.getId(), "Core");
    OrganizationUser member = new OrganizationUser(tenantId, "a@acme.com", "A", team.getId(), null);
    OrganizationUser unassigned = new OrganizationUser(tenantId, "b@acme.com", "B", null, null);
    when(companyRepository.findByTenantIdAndDeletedFalse(tenantId)).thenReturn(List.of(company));
    when(departmentRepository.findByTenantIdAndDeletedFalse(tenantId))
        .thenReturn(List.of(department));
    when(teamRepository.findByTenantIdAndDeletedFalse(tenantId)).thenReturn(List.of(team));
    when(organizationUserRepository.findByTenantIdAndDeletedFalse(tenantId))
        .thenReturn(List.of(member, unassigned));

    List<CompanyTreeResponse> tree = service.getTree(tenantId);

    assertThat(tree)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.departments())
                  .singleElement()
                  .satisfies(
                      d -> {
                        assertThat(d.teams())
                            .singleElement()
                            .satisfies(
                                t -> {
                                  assertThat(t.users())
                                      .extracting(OrgUserResponse::email)
                                      .containsExactly("a@acme.com");
                                });
                      });
            });
  }

  @Test
  void createUserWithoutAPositionKeepsTheExistingAccountRole() {
    UUID tenantId = UUID.randomUUID();

    OrgUserResponse response =
        service.createUser(tenantId, new OrgUserRequest("plain@acme.com", "Plain", null, null));

    assertThat(response.positionId()).isNull();
    verify(userRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void aNullCallerRoleSkipsTheAuthorityCheck() {
    PositionResponse response =
        service.createPosition(
            UUID.randomUUID(), new PositionRequest("Root", Role.SYSTEM_ADMIN), null);

    assertThat(response.role()).isEqualTo(Role.SYSTEM_ADMIN);
  }

  @Test
  void creatingADuplicateCompanyReturns409() {
    UUID tenantId = UUID.randomUUID();
    when(companyRepository.existsByTenantIdAndNameAndDeletedFalse(tenantId, "Acme"))
        .thenReturn(true);

    assertThatThrownBy(() -> service.createCompany(tenantId, new CompanyRequest("Acme")))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.ORG_CONFLICT));
  }

  @Test
  void creatingADuplicateDepartmentReturns409() {
    UUID tenantId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();
    when(companyRepository.findByIdAndTenantIdAndDeletedFalse(companyId, tenantId))
        .thenReturn(Optional.of(new Company(tenantId, "Acme")));
    when(departmentRepository.existsByTenantIdAndCompanyIdAndNameAndDeletedFalse(
            tenantId, companyId, "IT"))
        .thenReturn(true);

    assertThatThrownBy(
            () -> service.createDepartment(tenantId, new DepartmentRequest(companyId, null, "IT")))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.ORG_CONFLICT));
  }

  @Test
  void creatingADuplicateTeamReturns409() {
    UUID tenantId = UUID.randomUUID();
    UUID departmentId = UUID.randomUUID();
    when(departmentRepository.findByIdAndTenantIdAndDeletedFalse(departmentId, tenantId))
        .thenReturn(Optional.of(new Department(tenantId, UUID.randomUUID(), null, "IT")));
    when(teamRepository.existsByTenantIdAndDepartmentIdAndNameAndDeletedFalse(
            tenantId, departmentId, "Core"))
        .thenReturn(true);

    assertThatThrownBy(() -> service.createTeam(tenantId, new TeamRequest(departmentId, "Core")))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.ORG_CONFLICT));
  }

  @Test
  void creatingADuplicatePositionReturns409() {
    UUID tenantId = UUID.randomUUID();
    when(positionRepository.existsByTenantIdAndNameAndDeletedFalse(tenantId, "Finance"))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                service.createPosition(
                    tenantId, new PositionRequest("Finance", Role.EMPLOYEE), "TENANT_ADMIN"))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.ORG_CONFLICT));
  }

  @Test
  void creatingADuplicateOrganizationUserReturns409() {
    UUID tenantId = UUID.randomUUID();
    when(organizationUserRepository.existsByTenantIdAndEmailAndDeletedFalse(tenantId, "a@acme.com"))
        .thenReturn(true);

    assertThatThrownBy(
            () -> service.createUser(tenantId, new OrgUserRequest("a@acme.com", "A", null, null)))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.ORG_CONFLICT));
  }

  @Test
  void quotaExceededRejectsTheOrganizationUser() {
    UUID tenantId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(
            new AwapException(ErrorCode.QUOTA_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS, "quota"))
        .when(quotaService)
        .assertCanCreateUser(tenantId);

    assertThatThrownBy(
            () -> service.createUser(tenantId, new OrgUserRequest("a@acme.com", "A", null, null)))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex ->
                assertThat(((AwapException) ex).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void assignToTeamPublishesOutboxEvent() {
    UUID tenantId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    OrganizationUser user = new OrganizationUser(tenantId, "u@acme.com", "U", null, null);
    when(teamRepository.findByIdAndTenantIdAndDeletedFalse(teamId, tenantId))
        .thenReturn(Optional.of(new Team(tenantId, UUID.randomUUID(), "Core")));
    when(organizationUserRepository.findByIdAndTenantIdAndDeletedFalse(userId, tenantId))
        .thenReturn(Optional.of(user));

    OrgUserResponse response = service.assignToTeam(tenantId, userId, teamId);

    assertThat(response.teamId()).isEqualTo(teamId);
    verify(outboxEventPublisher)
        .publish(
            eq(tenantId),
            eq("ORGANIZATION"),
            eq(user.getId()),
            eq("organization.team.user_assigned"),
            any());
  }
}
