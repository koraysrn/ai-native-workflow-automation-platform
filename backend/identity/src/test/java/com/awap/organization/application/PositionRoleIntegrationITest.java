package com.awap.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.identity.IdentityApplication;
import com.awap.identity.application.AuthService;
import com.awap.identity.application.TokenPair;
import com.awap.identity.domain.Role;
import com.awap.identity.infrastructure.security.JwtService;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.organization.interfaces.dto.PositionResponse;
import com.awap.organization.interfaces.dto.PositionRoleRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL integration proof for position-based role assignment (issue #194): the V52 migration,
 * tenant-scoped lookups, authority propagation to identity accounts, audit/outbox writes, the next
 * login/refresh token claim and deterministic optimistic-locking behaviour are exercised against a
 * real database.
 */
@SpringBootTest(classes = IdentityApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class PositionRoleIntegrationITest {

  private static final String PASSWORD = "secret123";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
  }

  @Autowired private OrganizationService organizationService;
  @Autowired private AuthService authService;
  @Autowired private JwtService jwtService;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate transactionTemplate;
  private UUID tenantId;
  private UUID otherTenantId;
  private String tenantSlug;
  private UUID userId;
  private UUID positionId;
  private String email;

  @BeforeEach
  void seed() {
    transactionTemplate = new TransactionTemplate(transactionManager);
    tenantId = UUID.randomUUID();
    otherTenantId = UUID.randomUUID();
    tenantSlug = "tenant-" + tenantId;
    email = "user-" + UUID.randomUUID() + "@acme.com";
    insertTenant(tenantId, tenantSlug);
    insertTenant(otherTenantId, "other-" + otherTenantId);
    userId = insertUser(tenantId, email, Role.EMPLOYEE);
    positionId = insertPositionWithoutRole(tenantId, "Finance");
    insertOrganizationUser(tenantId, email, positionId);
    TenantContext.set(tenantId, userId, "TENANT_ADMIN");
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void migrationBackfillsTheSafeEmployeeDefaultAndRejectsUnknownRoles() {
    Map<String, Object> row =
        jdbcTemplate.queryForMap("SELECT role, version FROM positions WHERE id = ?", positionId);
    assertThat(row.get("role")).isEqualTo("EMPLOYEE");
    assertThat(((Number) row.get("version")).longValue()).isZero();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE positions SET role = 'SUPER_USER' WHERE id = ?", positionId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void roleChangePropagatesAndTheNextTokensCarryTheNewRole() {
    TokenPair tokens =
        inTenantTransaction(
            () -> {
              PositionResponse response =
                  organizationService.updatePositionRole(
                      tenantId,
                      positionId,
                      new PositionRoleRequest(Role.MANAGER),
                      "TENANT_ADMIN",
                      userId);
              assertThat(response.role()).isEqualTo(Role.MANAGER);
              return authService.login(tenantSlug, email, PASSWORD);
            });

    assertThat(jwtService.parse(tokens.accessToken()).get("role", String.class))
        .isEqualTo("MANAGER");

    TokenPair refreshed = inTenantTransaction(() -> authService.refresh(tokens.refreshToken()));
    assertThat(jwtService.parse(refreshed.accessToken()).get("role", String.class))
        .isEqualTo("MANAGER");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, userId))
        .isEqualTo("MANAGER");

    List<Map<String, Object>> audits =
        jdbcTemplate.queryForList(
            "SELECT action, old_value, new_value, actor_id FROM organization_audit_events"
                + " WHERE entity_id = ?",
            positionId);
    assertThat(audits)
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.get("action")).isEqualTo("ROLE_CHANGED");
              assertThat(audit.get("old_value")).isEqualTo("EMPLOYEE");
              assertThat(audit.get("new_value")).isEqualTo("MANAGER");
              assertThat(audit.get("actor_id")).isEqualTo(userId);
            });

    Map<String, Object> outbox =
        jdbcTemplate.queryForMap(
            "SELECT event_type, payload_json FROM outbox_events WHERE aggregate_id = ?",
            positionId);
    assertThat(outbox.get("event_type")).isEqualTo("organization.position.role_changed");
    assertThat((String) outbox.get("payload_json"))
        .contains("\"oldRole\":\"EMPLOYEE\"")
        .contains("\"newRole\":\"MANAGER\"")
        .contains(userId.toString());
  }

  @Test
  void aPositionFromAnotherTenantReturns404() {
    UUID foreignPosition = insertPositionWithoutRole(otherTenantId, "Foreign");

    assertThatThrownBy(
            () ->
                inTenantTransaction(
                    () ->
                        organizationService.updatePositionRole(
                            tenantId,
                            foreignPosition,
                            new PositionRoleRequest(Role.MANAGER),
                            "TENANT_ADMIN",
                            userId)))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(404));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT role FROM positions WHERE id = ?", String.class, foreignPosition))
        .isEqualTo("EMPLOYEE");
  }

  @Test
  void aStaleConcurrentUpdateIsRejectedDeterministically() {
    runInTenantTransaction(
        () ->
            organizationService.updatePositionRole(
                tenantId,
                positionId,
                new PositionRoleRequest(Role.OPERATIONS),
                "TENANT_ADMIN",
                userId));

    int stale =
        jdbcTemplate.update(
            "UPDATE positions SET role = 'MANAGER' WHERE id = ? AND version = 0", positionId);
    assertThat(stale).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT role FROM positions WHERE id = ?", String.class, positionId))
        .isEqualTo("OPERATIONS");

    int fresh =
        jdbcTemplate.update(
            "UPDATE positions SET role = 'MANAGER' WHERE id = ? AND version = 1", positionId);
    assertThat(fresh).isEqualTo(1);
  }

  private <T> T inTenantTransaction(Supplier<T> action) {
    return transactionTemplate.execute(
        status -> {
          jdbcTemplate.execute("SET LOCAL awap.tenant_id = '" + tenantId + "'");
          return action.get();
        });
  }

  private void runInTenantTransaction(Runnable action) {
    inTenantTransaction(
        () -> {
          action.run();
          return null;
        });
  }

  private void insertTenant(UUID id, String slug) {
    jdbcTemplate.update(
        "INSERT INTO tenants (id, slug, name, plan, max_users, max_workflows,"
            + " max_ai_calls_per_month, active, created_at, updated_at, deleted)"
            + " VALUES (?, ?, ?, 'FREE', 10, 10, 1000, true, now(), now(), false)",
        id,
        slug,
        slug);
  }

  private UUID insertUser(UUID tenant, String userEmail, Role role) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, tenant_id, email, password_hash, full_name, role,"
            + " created_at, updated_at, deleted, failed_login_attempts)"
            + " VALUES (?, ?, ?, ?, 'User', ?, now(), now(), false, 0)",
        id,
        tenant,
        userEmail,
        passwordEncoder.encode(PASSWORD),
        role.name());
    return id;
  }

  private UUID insertPositionWithoutRole(UUID tenant, String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO positions (id, tenant_id, name, created_at, updated_at, deleted)"
            + " VALUES (?, ?, ?, now(), now(), false)",
        id,
        tenant,
        name);
    return id;
  }

  private void insertOrganizationUser(UUID tenant, String userEmail, UUID position) {
    jdbcTemplate.update(
        "INSERT INTO organization_users"
            + " (id, tenant_id, email, full_name, position_id, created_at, updated_at, deleted)"
            + " VALUES (?, ?, ?, 'User', ?, now(), now(), false)",
        UUID.randomUUID(),
        tenant,
        userEmail,
        position);
  }
}
