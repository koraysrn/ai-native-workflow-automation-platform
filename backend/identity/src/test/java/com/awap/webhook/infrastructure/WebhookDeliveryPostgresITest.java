package com.awap.webhook.infrastructure;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies V38 against real PostgreSQL: unique idempotency key, row-level security, and the
 * SECURITY DEFINER claim helper (lease + attempts increment + skip while leased).
 */
@Testcontainers(disabledWithoutDocker = true)
class WebhookDeliveryPostgresITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @BeforeEach
  void clearDeliveries() throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = admin.createStatement()) {
      statement.executeUpdate("DELETE FROM webhook_deliveries");
    }
  }

  private static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
  }

  private static void setTenant(Connection connection, UUID tenantId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET awap.tenant_id = '" + tenantId + "'");
    }
  }

  private static void insertDelivery(Connection connection, UUID tenantId, String key)
      throws SQLException {
    setTenant(connection, tenantId);
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO webhook_deliveries (id, tenant_id, workflow_instance_id, node_execution_id,"
                + " node_id, target_url, payload_json, idempotency_key, status, attempts,"
                + " max_attempts, next_attempt_at, created_at, updated_at, expires_at)"
                + " VALUES (?, ?, ?, ?, 'hook', 'https://hooks.example.com/path', '{}', ?,"
                + " 'PENDING', 1, 3, now() - interval '1 minute', now(), now(),"
                + " now() + interval '7 days')")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, tenantId);
      statement.setObject(3, UUID.randomUUID());
      statement.setObject(4, UUID.randomUUID());
      statement.setString(5, key);
      statement.executeUpdate();
    }
  }

  private static int countVisible(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM webhook_deliveries")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void uniqueConstraintRejectsADuplicateIdempotencyKeyPerTenant() throws Exception {
    try (Connection connection = appConnection()) {
      insertDelivery(connection, TENANT_A, "dup-key");

      assertThatThrownBy(() -> insertDelivery(connection, TENANT_A, "dup-key"))
          .isInstanceOf(SQLException.class)
          .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("23505"));
    }
  }

  @Test
  void theSameIdempotencyKeyIsAllowedForADifferentTenant() throws Exception {
    try (Connection connection = appConnection()) {
      insertDelivery(connection, TENANT_A, "shared-key");
      insertDelivery(connection, TENANT_B, "shared-key");

      setTenant(connection, TENANT_A);
      assertThat(countVisible(connection)).isEqualTo(1);
      setTenant(connection, TENANT_B);
      assertThat(countVisible(connection)).isEqualTo(1);

      // A session without a tenant context sees nothing at all.
      try (Connection anonymous = appConnection()) {
        assertThat(countVisible(anonymous)).isZero();
      }
    }
  }

  @Test
  void rowLevelSecurityHidesOtherTenantsRows() throws Exception {
    try (Connection connection = appConnection()) {
      insertDelivery(connection, TENANT_A, "rls-key-a");

      setTenant(connection, TENANT_B);
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT count(*) FROM webhook_deliveries")) {
        rs.next();
        assertThat(rs.getInt(1)).isZero();
      }

      setTenant(connection, TENANT_A);
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT count(*) FROM webhook_deliveries")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  void claimHelperLeasesDueRowsAndSkipsLiveLeases() throws Exception {
    try (Connection connection = appConnection()) {
      insertDelivery(connection, TENANT_A, "claim-key");

      assertThat(claim(connection, 10, 30)).isEqualTo(1);
      // The lease is live: a second replica receives nothing.
      assertThat(claim(connection, 10, 30)).isZero();

      setTenant(connection, TENANT_A);
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE webhook_deliveries SET lease_until = now() - interval '1 second'");
      }

      assertThat(claim(connection, 10, 30)).isEqualTo(1);
      setTenant(connection, TENANT_A);
      try (Statement statement = connection.createStatement();
          ResultSet rs =
              statement.executeQuery(
                  "SELECT status, attempts, lease_token FROM webhook_deliveries")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("status")).isEqualTo("RETRYING");
        assertThat(rs.getInt("attempts")).isEqualTo(3);
        assertThat(rs.getString("lease_token")).isNotBlank();
      }
    }
  }

  @Test
  void expiredRowsAreNotClaimed() throws Exception {
    try (Connection connection = appConnection()) {
      setTenant(connection, TENANT_A);
      try (var statement =
          connection.prepareStatement(
              "INSERT INTO webhook_deliveries (id, tenant_id, workflow_instance_id,"
                  + " node_execution_id, node_id, target_url, payload_json, idempotency_key,"
                  + " status, attempts, max_attempts, next_attempt_at, created_at, updated_at,"
                  + " expires_at) VALUES (?, ?, ?, ?, 'hook', 'https://hooks.example.com', '{}',"
                  + " 'expired-key', 'PENDING', 1, 3, now() - interval '1 minute', now(), now(),"
                  + " now() - interval '1 second')")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, TENANT_A);
        statement.setObject(3, UUID.randomUUID());
        statement.setObject(4, UUID.randomUUID());
        statement.executeUpdate();
      }

      assertThat(claim(connection, 10, 30)).isZero();
    }
  }

  private static int claim(Connection connection, int limit, int leaseSeconds) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT count(*) FROM awap_claim_due_webhook_deliveries(now(), ?, ?)")) {
      statement.setInt(1, limit);
      statement.setInt(2, leaseSeconds);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
