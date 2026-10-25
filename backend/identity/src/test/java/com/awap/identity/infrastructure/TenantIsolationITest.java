package com.awap.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves cross-tenant isolation end-to-end against real PostgreSQL row-level security (issue #86).
 *
 * <p>The assertions run as the non-owner {@code awap_app} role (created by V12), so the mandatory
 * RLS policies from V13/V21 are exercised rather than the container superuser's owner bypass.
 * Docker-less environments skip the test via {@code disabledWithoutDocker = true}.
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantIsolationITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  @BeforeAll
  static void migrateAndSeed() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.executeUpdate(tenantInsert(TENANT_A, "tenant-a"));
      stmt.executeUpdate(tenantInsert(TENANT_B, "tenant-b"));
      stmt.executeUpdate(userInsert(TENANT_A, "a@example.com"));
      stmt.executeUpdate(userInsert(TENANT_B, "b@example.com"));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to seed tenants", ex);
    }
  }

  @Test
  void rlsScopesSelectToConfiguredTenant() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");
      try (ResultSet rs = stmt.executeQuery("SELECT email FROM users ORDER BY email")) {
        assertThat(emails(rs)).containsExactly("a@example.com");
      }
    }
  }

  @Test
  void unsetTenantYieldsZeroRows() throws Exception {
    // Fresh session with awap.tenant_id never set: current_setting(..., true)
    // returns NULL, so the V13 predicate evaluates to null and filters every row.
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
        rs.next();
        assertThat(rs.getLong(1)).isZero();
      }
    }
  }

  @Test
  void wrongTenantYieldsZeroRows() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + UUID.randomUUID() + "'");
      try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
        rs.next();
        assertThat(rs.getLong(1)).isZero();
      }
    }
  }

  @Test
  void crossTenantUpdateAffectsZeroRows() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");
      int updated =
          stmt.executeUpdate(
              "UPDATE users SET full_name = 'hacked' WHERE tenant_id = '" + TENANT_B + "'");
      assertThat(updated).isZero();
    }
  }

  private static Connection adminConnection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection appConnection() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
  }

  private static java.util.List<String> emails(ResultSet rs) throws Exception {
    java.util.List<String> emails = new java.util.ArrayList<>();
    while (rs.next()) {
      emails.add(rs.getString(1));
    }
    return emails;
  }

  private static String tenantInsert(UUID id, String slug) {
    return "INSERT INTO tenants (id, slug, name, plan, max_users, max_workflows,"
        + " max_ai_calls_per_month, active, created_at, updated_at, deleted)"
        + " VALUES ('"
        + id
        + "', '"
        + slug
        + "', '"
        + slug
        + "', 'FREE', 10, 10, 1000,"
        + " true, now(), now(), false)";
  }

  private static String userInsert(UUID tenantId, String email) {
    return "INSERT INTO users (id, tenant_id, email, password_hash, full_name, role,"
        + " created_at, updated_at, deleted)"
        + " VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + tenantId
        + "', '"
        + email
        + "', 'x', 'User', 'MEMBER', now(), now(), false)";
  }
}
