package com.awap.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.identity.domain.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Entity invariants and accessors for the organization hierarchy (issue #195). */
class OrganizationDomainTest {

  private final UUID tenantId = UUID.randomUUID();

  @Test
  void companyExposesEveryField() {
    Company company = new Company(tenantId, "Acme");

    assertThat(company.getId()).isNotNull();
    assertThat(company.getTenantId()).isEqualTo(tenantId);
    assertThat(company.getName()).isEqualTo("Acme");
    assertThat(company.getCreatedAt()).isNotNull();
    assertThat(company.getUpdatedAt()).isNotNull();
    assertThat(company.isDeleted()).isFalse();
  }

  @Test
  void departmentKeepsItsCompanyAndOptionalParent() {
    UUID companyId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();

    Department department = new Department(tenantId, companyId, parentId, "IT");

    assertThat(department.getId()).isNotNull();
    assertThat(department.getTenantId()).isEqualTo(tenantId);
    assertThat(department.getCompanyId()).isEqualTo(companyId);
    assertThat(department.getParentId()).isEqualTo(parentId);
    assertThat(department.getName()).isEqualTo("IT");
    assertThat(department.getCreatedAt()).isNotNull();
    assertThat(department.getUpdatedAt()).isNotNull();
    assertThat(department.isDeleted()).isFalse();
  }

  @Test
  void teamKeepsItsDepartment() {
    UUID departmentId = UUID.randomUUID();

    Team team = new Team(tenantId, departmentId, "Core");

    assertThat(team.getId()).isNotNull();
    assertThat(team.getTenantId()).isEqualTo(tenantId);
    assertThat(team.getDepartmentId()).isEqualTo(departmentId);
    assertThat(team.getName()).isEqualTo("Core");
    assertThat(team.getCreatedAt()).isNotNull();
    assertThat(team.getUpdatedAt()).isNotNull();
    assertThat(team.isDeleted()).isFalse();
  }

  @Test
  void organizationUserAssignToTeamUpdatesTheTeamAndTimestamp() {
    UUID positionId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    OrganizationUser user = new OrganizationUser(tenantId, "a@acme.com", "A", null, positionId);
    Instant before = user.getUpdatedAt();

    user.assignToTeam(teamId);

    assertThat(user.getId()).isNotNull();
    assertThat(user.getTenantId()).isEqualTo(tenantId);
    assertThat(user.getEmail()).isEqualTo("a@acme.com");
    assertThat(user.getFullName()).isEqualTo("A");
    assertThat(user.getTeamId()).isEqualTo(teamId);
    assertThat(user.getPositionId()).isEqualTo(positionId);
    assertThat(user.getCreatedAt()).isNotNull();
    assertThat(user.getUpdatedAt()).isAfterOrEqualTo(before);
    assertThat(user.isDeleted()).isFalse();
  }

  @Test
  void auditEventCapturesTheChange() {
    UUID entityId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();

    OrganizationAuditEvent event =
        new OrganizationAuditEvent(
            tenantId, "POSITION", entityId, "ROLE_CHANGED", "EMPLOYEE", "MANAGER", actorId);

    assertThat(event.getId()).isNotNull();
    assertThat(event.getTenantId()).isEqualTo(tenantId);
    assertThat(event.getEntityType()).isEqualTo("POSITION");
    assertThat(event.getEntityId()).isEqualTo(entityId);
    assertThat(event.getAction()).isEqualTo("ROLE_CHANGED");
    assertThat(event.getOldValue()).isEqualTo("EMPLOYEE");
    assertThat(event.getNewValue()).isEqualTo("MANAGER");
    assertThat(event.getActorId()).isEqualTo(actorId);
    assertThat(event.getCreatedAt()).isNotNull();
  }

  @Test
  void positionDefaultsToEmployeeAndKeepsExplicitRoles() {
    assertThat(new Position(tenantId, "Intern").getRole()).isEqualTo(Role.EMPLOYEE);
    assertThat(new Position(tenantId, "Finance", Role.MANAGER).getRole()).isEqualTo(Role.MANAGER);

    Position position = new Position(tenantId, "Finance", Role.EMPLOYEE);
    position.changeRole(Role.OPERATIONS);
    assertThat(position.getRole()).isEqualTo(Role.OPERATIONS);
  }
}
