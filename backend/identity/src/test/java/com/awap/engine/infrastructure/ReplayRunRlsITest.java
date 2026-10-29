package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the V40 {@code replay_runs} audit table is tenant-isolated by real PostgreSQL RLS.
 * Assertions run as the non-owner {@code awap_app} role, so the policy is actually exercised.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReplayRunRlsITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  @BeforeAll
  static void migrateAndSeed() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.executeUpdate(insert(TENANT_A, UUID.randomUUID(), "MATCHED", false));
      stmt.executeUpdate(insert(TENANT_B, UUID.randomUUID(), "DIVERGED", true));
    }
  }

  @Test
  void rlsScopesReplayRunsToTheConfiguredTenant() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");
      try (ResultSet rs = stmt.executeQuery("SELECT result FROM replay_runs")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("result")).isEqualTo("MATCHED");
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void unsetTenantYieldsZeroRows() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT count(*) FROM replay_runs")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  @Test
  void crossTenantInsertIsRejectedByTheWithCheckPolicy() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");

      assertThatThrownBy(
              () -> stmt.executeUpdate(insert(TENANT_B, UUID.randomUUID(), "MATCHED", false)))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("row-level security");
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
  }

  private static String insert(UUID tenantId, UUID instanceId, String result, boolean divergence) {
    return "INSERT INTO replay_runs (id, tenant_id, workflow_instance_id, dry_run, result,"
        + " divergence, divergence_count, state_hash, log_rows, log_pages, duration_ms,"
        + " created_at) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + tenantId
        + "', '"
        + instanceId
        + "', "
        + divergence
        + ", '"
        + result
        + "', "
        + divergence
        + ", 0, '"
        + "0".repeat(64)
        + "', 1, 1, 1, now())";
  }
}
