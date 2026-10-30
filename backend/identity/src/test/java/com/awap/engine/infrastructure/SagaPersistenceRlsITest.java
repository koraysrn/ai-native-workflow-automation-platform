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
 * PostgreSQL proof for issue #133: the saga tables apply the V49 migration, RLS isolates tenants
 * through the non-owner {@code awap_app} role and the recovery claim function sees all due sagas
 * without a tenant context (the scheduler is a system task).
 */
@Testcontainers(disabledWithoutDocker = true)
class SagaPersistenceRlsITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();
  private static final UUID SAGA_A = UUID.randomUUID();
  private static final UUID SAGA_B = UUID.randomUUID();

  @BeforeAll
  static void migrateAndSeed() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.executeUpdate(insertSaga(SAGA_A, TENANT_A, "workflow:" + SAGA_A));
      stmt.executeUpdate(insertSaga(SAGA_B, TENANT_B, "workflow:" + SAGA_B));
      stmt.executeUpdate(insertStep(TENANT_A, SAGA_A, "erp-step", 1));
      stmt.executeUpdate(insertStep(TENANT_B, SAGA_B, "crm-step", 1));
    }
  }

  @Test
  void rlsScopesSagasAndStepsToTheConfiguredTenant() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");
      try (ResultSet rs = stmt.executeQuery("SELECT saga_key FROM saga_instances")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("saga_key")).isEqualTo("workflow:" + SAGA_A);
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = stmt.executeQuery("SELECT node_id FROM saga_steps")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("node_id")).isEqualTo("erp-step");
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void unsetTenantYieldsZeroSagaRows() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT count(*) FROM saga_instances")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  @Test
  void crossTenantSagaInsertIsRejectedByTheWithCheckPolicy() throws Exception {
    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");

      assertThatThrownBy(
              () ->
                  stmt.executeUpdate(
                      insertSaga(UUID.randomUUID(), TENANT_B, "workflow:cross-tenant")))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("row-level security");
    }
  }

  @Test
  void recoveryClaimFunctionSeesDueSagasAcrossTenants() throws Exception {
    UUID leaseToken = UUID.randomUUID();
    try (Connection app = appConnection();
        Statement stmt = app.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT tenant_id, saga_instance_id"
                    + " FROM awap_claim_saga_compensations(now(), '"
                    + leaseToken
                    + "', 30, 10)")) {
      int rows = 0;
      while (rs.next()) {
        assertThat(rs.getObject("tenant_id", UUID.class)).isIn(TENANT_A, TENANT_B);
        assertThat(rs.getObject("saga_instance_id", UUID.class)).isIn(SAGA_A, SAGA_B);
        rows++;
      }
      assertThat(rows).isEqualTo(2);
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
  }

  private static String insertSaga(UUID sagaId, UUID tenantId, String sagaKey) {
    return "INSERT INTO saga_instances (id, tenant_id, workflow_instance_id, saga_key, status,"
        + " next_compensation_at, compensation_started_at, version, created_at, updated_at,"
        + " deleted) VALUES ('"
        + sagaId
        + "', '"
        + tenantId
        + "', '"
        + UUID.randomUUID()
        + "', '"
        + sagaKey
        + "', 'COMPENSATING', now() - interval '1 minute', now() - interval '1 minute',"
        + " 0, now(), now(), false)";
  }

  private static String insertStep(UUID tenantId, UUID sagaId, String nodeId, int order) {
    return "INSERT INTO saga_steps (id, tenant_id, saga_instance_id, step_order, node_id,"
        + " node_type, action_type, action_payload, compensation_type, compensation_payload,"
        + " idempotency_key, status, attempts, max_attempts, retry_backoff_ms, timeout_seconds,"
        + " version, created_at, updated_at) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + tenantId
        + "', '"
        + sagaId
        + "', "
        + order
        + ", '"
        + nodeId
        + "', 'WEBHOOK', 'WEBHOOK', '{}', 'WEBHOOK',"
        + " '{\"url\":\"https://erp.example.com/rollback\",\"payload\":\"{}\"}',"
        + " 'saga:"
        + sagaId
        + ":node:"
        + nodeId
        + "', 'SUCCEEDED', 0, 3, 1000, 10, 0, now(), now())";
  }
}
