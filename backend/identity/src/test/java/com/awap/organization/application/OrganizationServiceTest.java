package com.awap.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.application.QuotaService;
import com.awap.organization.domain.OrganizationUser;
import com.awap.organization.domain.Team;
import com.awap.organization.infrastructure.CompanyRepository;
import com.awap.organization.infrastructure.DepartmentRepository;
import com.awap.organization.infrastructure.OrganizationUserRepository;
import com.awap.organization.infrastructure.PositionRepository;
import com.awap.organization.infrastructure.TeamRepository;
import com.awap.organization.interfaces.dto.CompanyRequest;
import com.awap.organization.interfaces.dto.CompanyResponse;
import com.awap.organization.interfaces.dto.DepartmentRequest;
import com.awap.organization.interfaces.dto.OrgUserResponse;
import com.awap.organization.interfaces.dto.OrganizationMapperImpl;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private DepartmentRepository departmentRepository;
  @Mock private TeamRepository teamRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private OrganizationUserRepository organizationUserRepository;
  @Mock private QuotaService quotaService;
  @Mock private OutboxEventPublisher outboxEventPublisher;

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
            new OrganizationMapperImpl());
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
