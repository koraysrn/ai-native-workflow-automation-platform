package com.awap.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.identity.domain.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PositionTest {

  private final UUID tenantId = UUID.randomUUID();

  @Test
  void defaultsToEmployeeWhenNoRoleIsGiven() {
    assertThat(new Position(tenantId, "Intern").getRole()).isEqualTo(Role.EMPLOYEE);
    assertThat(new Position(tenantId, "Intern", null).getRole()).isEqualTo(Role.EMPLOYEE);
  }

  @Test
  void keepsAnExplicitRole() {
    Position position = new Position(tenantId, "Finance", Role.MANAGER);
    assertThat(position.getRole()).isEqualTo(Role.MANAGER);
    assertThat(position.getName()).isEqualTo("Finance");
    assertThat(position.getId()).isNotNull();
    assertThat(position.getTenantId()).isEqualTo(tenantId);
    assertThat(position.getCreatedAt()).isNotNull();
    assertThat(position.getUpdatedAt()).isNotNull();
  }

  @Test
  void changesTheRoleAndRejectsNull() {
    Position position = new Position(tenantId, "Finance", Role.EMPLOYEE);

    position.changeRole(Role.OPERATIONS);

    assertThat(position.getRole()).isEqualTo(Role.OPERATIONS);
    assertThatThrownBy(() -> position.changeRole(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("role is required");
  }

  @Test
  void roleAuthorityOrderAllowsOnlyOwnOrLowerRoles() {
    assertThat(Role.SYSTEM_ADMIN.canAssign(Role.SYSTEM_ADMIN)).isTrue();
    assertThat(Role.SYSTEM_ADMIN.canAssign(Role.EMPLOYEE)).isTrue();
    assertThat(Role.TENANT_ADMIN.canAssign(Role.MANAGER)).isTrue();
    assertThat(Role.TENANT_ADMIN.canAssign(Role.SYSTEM_ADMIN)).isFalse();
    assertThat(Role.MANAGER.canAssign(Role.TENANT_ADMIN)).isFalse();
    assertThat(Role.OPERATIONS.canAssign(Role.MANAGER)).isFalse();
    assertThat(Role.EMPLOYEE.canAssign(Role.EMPLOYEE)).isTrue();
    assertThat(Role.EMPLOYEE.canAssign(null)).isFalse();
  }
}
