package com.awap.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.application.QuotaService;
import com.awap.identity.domain.Role;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.organization.infrastructure.CompanyRepository;
import com.awap.organization.infrastructure.DepartmentRepository;
import com.awap.organization.infrastructure.OrganizationAuditEventRepository;
import com.awap.organization.infrastructure.OrganizationUserRepository;
import com.awap.organization.infrastructure.PositionRepository;
import com.awap.organization.infrastructure.TeamRepository;
import com.awap.organization.interfaces.dto.OrganizationMapperImpl;
import com.awap.organization.interfaces.dto.PositionRequest;
import com.awap.organization.interfaces.dto.PositionResponse;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exhaustive authorization matrix for position-role assignment (issue #194): every caller role is
 * exercised against every supported target role, proving that a caller can only assign its own role
 * or a lower one.
 */
@ExtendWith(MockitoExtension.class)
class PositionRoleAuthorizationTest {

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

  static Stream<Arguments> rolePairs() {
    return Stream.of(Role.values())
        .flatMap(caller -> Stream.of(Role.values()).map(target -> Arguments.of(caller, target)));
  }

  @ParameterizedTest(name = "{0} assigning {1}")
  @MethodSource("rolePairs")
  void enforcesTheRoleHierarchyForEveryRolePair(Role caller, Role target) {
    UUID tenantId = UUID.randomUUID();
    boolean allowed = target.ordinal() >= caller.ordinal();

    if (allowed) {
      PositionResponse response =
          service.createPosition(tenantId, new PositionRequest("Position", target), caller.name());

      assertThat(response.role()).isEqualTo(target);
    } else {
      assertThatThrownBy(
              () ->
                  service.createPosition(
                      tenantId, new PositionRequest("Position", target), caller.name()))
          .isInstanceOf(AwapException.class)
          .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(403));
      verifyNoInteractions(positionRepository, outboxEventPublisher);
    }
  }
}
