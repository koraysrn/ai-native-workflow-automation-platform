package com.awap.organization.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.application.QuotaService;
import com.awap.organization.infrastructure.CompanyRepository;
import com.awap.organization.infrastructure.DepartmentRepository;
import com.awap.organization.infrastructure.OrganizationUserRepository;
import com.awap.organization.infrastructure.PositionRepository;
import com.awap.organization.infrastructure.TeamRepository;
import com.awap.organization.interfaces.dto.DepartmentRequest;
import com.awap.organization.interfaces.dto.OrganizationMapper;
import com.awap.organization.interfaces.dto.TeamRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Negative organization paths: creating child nodes under missing parents. */
class OrganizationServiceNegativeTest {

  private OrganizationService service;
  private CompanyRepository companyRepository;
  private DepartmentRepository departmentRepository;
  private final UUID tenantId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    companyRepository = mock(CompanyRepository.class);
    departmentRepository = mock(DepartmentRepository.class);
    service =
        new OrganizationService(
            companyRepository,
            departmentRepository,
            mock(TeamRepository.class),
            mock(PositionRepository.class),
            mock(OrganizationUserRepository.class),
            mock(QuotaService.class),
            mock(OutboxEventPublisher.class),
            mock(OrganizationMapper.class));
  }

  @Test
  void createDepartmentRejectsMissingCompany() {
    UUID companyId = UUID.randomUUID();
    when(companyRepository.findByIdAndTenantIdAndDeletedFalse(companyId, tenantId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.createDepartment(
                    tenantId, new DepartmentRequest(companyId, null, "Engineering")))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void createTeamRejectsMissingDepartment() {
    UUID departmentId = UUID.randomUUID();
    when(departmentRepository.findByIdAndTenantIdAndDeletedFalse(departmentId, tenantId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.createTeam(tenantId, new TeamRequest(departmentId, "Platform")))
        .isInstanceOf(AwapException.class);
  }
}
