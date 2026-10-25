package com.awap.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the Flyway migration chain and the PostgreSQL-specific helpers (V30 auth-bypass full row,
 * V32 monthly partition helper) against a real PostgreSQL 16 instance (issue #88).
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseMigrationITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static Flyway flyway() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load();
  }

  private static Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection app() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
  }

  @BeforeAll
  static void migrate() {
    flyway().migrate();
  }

  @Test
  @Order(1)
  void allMigrationsApplyCleanlyAndValidate() throws Exception {
    // migrate() already succeeded in @BeforeAll; validate() must not throw and no
    // applied migration may have failed.
    assertThatNoException().isThrownBy(() -> flyway().validate());
    try (Connection c = admin();
        Statement stmt = c.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success = false")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  @Test
  @Order(2)
  void v30AuthBypassFunctionsReturnTheFullRowIncludingAuditColumns() throws Exception {
    UUID tenantId = UUID.randomUUID();
    try (Connection c = admin();
        Statement stmt = c.createStatement()) {
      stmt.executeUpdate(
          "INSERT INTO tenants (id, slug, name, plan, max_users, max_workflows,"
              + " max_ai_calls_per_month, active, created_at, updated_at, deleted)"
              + " VALUES ('"
              + tenantId
              + "', 'acme', 'Acme', 'FREE', 10, 10, 1000,"
              + " true, now(), now(), false)");
    }

    // awap_app is a non-owner runtime role; the SECURITY DEFINER function bypasses RLS
    // and returns the full tenants row (SETOF tenants), including created_by/updated_by.
    try (Connection app = app();
        Statement stmt = app.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM awap_find_tenant_by_slug('acme')")) {
      ResultSetMetaData meta = rs.getMetaData();
      Set<String> columns = new HashSet<>();
      for (int i = 1; i <= meta.getColumnCount(); i++) {
        columns.add(meta.getColumnName(i).toLowerCase());
      }
      assertThat(columns).contains("created_by", "updated_by", "slug", "name");
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("slug")).isEqualTo("acme");
    }
  }

  @Test
  @Order(3)
  void v32MonthlyPartitionHelperCreatesTheRequestedPartition() throws Exception {
    try (Connection c = admin();
        Statement stmt = c.createStatement()) {
      stmt.execute("SELECT awap_ensure_outbox_monthly_partition(DATE '2026-01-01')");
      try (ResultSet rs =
          stmt.executeQuery("SELECT to_regclass('public.outbox_events_2026_01') IS NOT NULL")) {
        rs.next();
        assertThat(rs.getBoolean(1)).isTrue();
      }
    }
  }

  @Test
  @Order(4)
  void checksumMismatchFailsFastOnValidate() throws Exception {
    try (Connection c = admin();
        Statement stmt = c.createStatement()) {
      int original;
      try (ResultSet rs =
          stmt.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version = '1'")) {
        rs.next();
        original = rs.getInt(1);
      }

      stmt.executeUpdate(
          "UPDATE flyway_schema_history SET checksum = " + (original + 1) + " WHERE version = '1'");
      assertThatThrownBy(() -> flyway().validate()).isInstanceOf(FlywayValidateException.class);

      // Restore the checksum so the shared container stays consistent.
      stmt.executeUpdate(
          "UPDATE flyway_schema_history SET checksum = " + original + " WHERE version = '1'");
    }
  }
}
