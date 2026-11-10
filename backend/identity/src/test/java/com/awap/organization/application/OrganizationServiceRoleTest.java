package com.awap.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.application.QuotaService;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.organization.domain.OrganizationAuditEvent;
import com.awap.organization.domain.OrganizationUser;
import com.awap.organization.domain.Position;
import com.awap.organization.infrastructure.CompanyRepository;
import com.awap.organization.infrastructure.DepartmentRepository;
import com.awap.organization.infrastructure.OrganizationAuditEventRepository;
import com.awap.organization.infrastructure.OrganizationUserRepository;
import com.awap.organization.infrastructure.PositionRepository;
import com.awap.organization.infrastructure.TeamRepository;
import com.awap.organization.interfaces.dto.OrgUserRequest;
import com.awap.organization.interfaces.dto.OrgUserResponse;
import com.awap.organization.interfaces.dto.OrganizationMapperImpl;
import com.awap.organization.interfaces.dto.PositionRequest;
import com.awap.organization.interfaces.dto.PositionResponse;
import com.awap.organization.interfaces.dto.PositionRoleRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceRoleTest {

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
  private final UUID tenantId = UUID.randomUUID();

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
  void createsAPositionWithTheRequestedRole() {
    PositionResponse response =
        service.createPosition(
            tenantId, new PositionRequest("Finance", Role.MANAGER), "TENANT_ADMIN");

    assertThat(response.role()).isEqualTo(Role.MANAGER);
    assertThat(response.name()).isEqualTo("Finance");
  }

  @Test
  void defaultsAMissingRoleToEmployee() {
    PositionResponse response =
        service.createPosition(tenantId, new PositionRequest("Intern", null), "TENANT_ADMIN");

    assertThat(response.role()).isEqualTo(Role.EMPLOYEE);
  }

  @Test
  void rejectsCreatingAPositionAboveTheCallersAuthority() {
    assertThatThrownBy(
            () ->
                service.createPosition(
                    tenantId, new PositionRequest("Root", Role.SYSTEM_ADMIN), "TENANT_ADMIN"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(403));
  }

  @Test
  void rejectsAnUnknownCallerRole() {
    assertThatThrownBy(
            () ->
                service.createPosition(
                    tenantId, new PositionRequest("Finance", Role.MANAGER), "MADE_UP"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void changingTheRolePropagatesToUsersAndWritesAuditAndOutbox() {
    UUID positionId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    Position position = new Position(tenantId, "Finance", Role.EMPLOYEE);
    OrganizationUser member = new OrganizationUser(tenantId, "a@acme.com", "A", null, positionId);
    User user = new User(tenantId, "a@acme.com", "hash", "A", Role.EMPLOYEE);
    when(positionRepository.findByIdAndTenantIdAndDeletedFalse(positionId, tenantId))
        .thenReturn(Optional.of(position));
    when(organizationUserRepository.findByTenantIdAndPositionIdAndDeletedFalse(
            tenantId, positionId))
        .thenReturn(List.of(member));
    when(userRepository.findByTenantIdAndEmailRls(tenantId, "a@acme.com"))
        .thenReturn(Optional.of(user));

    PositionResponse response =
        service.updatePositionRole(
            tenantId, positionId, new PositionRoleRequest(Role.MANAGER), "TENANT_ADMIN", actorId);

    assertThat(response.role()).isEqualTo(Role.MANAGER);
    assertThat(user.getRole()).isEqualTo(Role.MANAGER);
    verify(userRepository).save(user);

    ArgumentCaptor<OrganizationAuditEvent> auditCaptor =
        ArgumentCaptor.forClass(OrganizationAuditEvent.class);
    verify(auditEventRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getAction()).isEqualTo("ROLE_CHANGED");
    assertThat(auditCaptor.getValue().getOldValue()).isEqualTo("EMPLOYEE");
    assertThat(auditCaptor.getValue().getNewValue()).isEqualTo("MANAGER");
    assertThat(auditCaptor.getValue().getActorId()).isEqualTo(actorId);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(outboxEventPublisher)
        .publish(
            eq(tenantId),
            eq("ORGANIZATION"),
            eq(positionId),
            eq("organization.position.role_changed"),
            payloadCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
    assertThat(payload)
        .containsEntry("oldRole", "EMPLOYEE")
        .containsEntry("newRole", "MANAGER")
        .containsEntry("affectedUserIds", List.of(user.getId()));
  }

  @Test
  void settingTheSameRoleIsADeterministicNoOp() {
    UUID positionId = UUID.randomUUID();
    Position position = new Position(tenantId, "Finance", Role.MANAGER);
    when(positionRepository.findByIdAndTenantIdAndDeletedFalse(positionId, tenantId))
        .thenReturn(Optional.of(position));

    PositionResponse response =
        service.updatePositionRole(
            tenantId,
            positionId,
            new PositionRoleRequest(Role.MANAGER),
            "TENANT_ADMIN",
            UUID.randomUUID());

    assertThat(response.role()).isEqualTo(Role.MANAGER);
    verifyNoInteractions(auditEventRepository);
    verify(outboxEventPublisher, never())
        .publish(any(), any(), any(), eq("organization.position.role_changed"), any());
  }

  @Test
  void rejectsAnEscalationOnUpdate() {
    UUID positionId = UUID.randomUUID();
    Position position = new Position(tenantId, "Finance", Role.EMPLOYEE);
    when(positionRepository.findByIdAndTenantIdAndDeletedFalse(positionId, tenantId))
        .thenReturn(Optional.of(position));

    assertThatThrownBy(
            () ->
                service.updatePositionRole(
                    tenantId,
                    positionId,
                    new PositionRoleRequest(Role.SYSTEM_ADMIN),
                    "TENANT_ADMIN",
                    UUID.randomUUID()))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(403));
    assertThat(position.getRole()).isEqualTo(Role.EMPLOYEE);
  }

  @Test
  void rejectsAPositionFromAnotherTenant() {
    UUID positionId = UUID.randomUUID();
    when(positionRepository.findByIdAndTenantIdAndDeletedFalse(positionId, tenantId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.updatePositionRole(
                    tenantId,
                    positionId,
                    new PositionRoleRequest(Role.MANAGER),
                    "TENANT_ADMIN",
                    UUID.randomUUID()))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(404));
  }

  @Test
  void creatingAUserAppliesThePositionRoleToAnExistingAccount() {
    UUID positionId = UUID.randomUUID();
    Position position = new Position(tenantId, "Finance", Role.OPERATIONS);
    User user = new User(tenantId, "new@acme.com", "hash", "New", Role.EMPLOYEE);
    when(positionRepository.findByIdAndTenantIdAndDeletedFalse(positionId, tenantId))
        .thenReturn(Optional.of(position));
    when(userRepository.findByTenantIdAndEmailRls(tenantId, "new@acme.com"))
        .thenReturn(Optional.of(user));

    OrgUserResponse response =
        service.createUser(tenantId, new OrgUserRequest("new@acme.com", "New", null, positionId));

    assertThat(response.positionId()).isEqualTo(positionId);
    assertThat(user.getRole()).isEqualTo(Role.OPERATIONS);
    verify(userRepository).save(user);
  }

  @Test
  void creatingAUserWithAForeignPositionReturns404() {
    UUID positionId = UUID.randomUUID();
    when(positionRepository.findByIdAndTenantIdAndDeletedFalse(positionId, tenantId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.createUser(
                    tenantId, new OrgUserRequest("new@acme.com", "New", null, positionId)))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(404));
  }

  @Test
  void aBlankCallerRoleSkipsTheAuthorityCheck() {
    PositionResponse response =
        service.createPosition(tenantId, new PositionRequest("Root", Role.SYSTEM_ADMIN), " ");

    assertThat(response.role()).isEqualTo(Role.SYSTEM_ADMIN);
  }

  @Test
  void aNullRoleOnUpdateIsRejectedWith400() {
    UUID positionId = UUID.randomUUID();
    Position position = new Position(tenantId, "Finance", Role.EMPLOYEE);
    when(positionRepository.findByIdAndTenantIdAndDeletedFalse(positionId, tenantId))
        .thenReturn(Optional.of(position));

    assertThatThrownBy(
            () ->
                service.updatePositionRole(
                    tenantId, positionId, new PositionRoleRequest(null), "TENANT_ADMIN", null))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
    assertThat(position.getRole()).isEqualTo(Role.EMPLOYEE);
  }

  @Test
  void roleChangeLeavesMembersWithoutAnIdentityAccountUntouched() {
    UUID positionId = UUID.randomUUID();
    Position position = new Position(tenantId, "Finance", Role.EMPLOYEE);
    OrganizationUser member =
        new OrganizationUser(tenantId, "ghost@acme.com", "G", null, positionId);
    when(positionRepository.findByIdAndTenantIdAndDeletedFalse(positionId, tenantId))
        .thenReturn(Optional.of(position));
    when(organizationUserRepository.findByTenantIdAndPositionIdAndDeletedFalse(
            tenantId, positionId))
        .thenReturn(List.of(member));
    when(userRepository.findByTenantIdAndEmailRls(tenantId, "ghost@acme.com"))
        .thenReturn(Optional.empty());

    PositionResponse response =
        service.updatePositionRole(
            tenantId,
            positionId,
            new PositionRoleRequest(Role.OPERATIONS),
            "TENANT_ADMIN",
            UUID.randomUUID());

    assertThat(response.role()).isEqualTo(Role.OPERATIONS);
    verify(userRepository, never()).save(any());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(outboxEventPublisher)
        .publish(
            eq(tenantId),
            eq("ORGANIZATION"),
            eq(positionId),
            eq("organization.position.role_changed"),
            payloadCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
    assertThat(payload).containsEntry("affectedUserIds", List.of());
  }
}
