package com.awap.usage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the metering SQL against real PostgreSQL: idempotency, atomic quota boundary under
 * concurrency, RLS isolation, reconciliation and retention/archive.
 */
@Testcontainers(disabledWithoutDocker = true)
class UsageCounterPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("awap")
          .withCommand("postgres", "-c", "max_connections=200");

  private static final LocalDate DAY = LocalDate.of(2026, 2, 10);

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @Test
  void duplicateEventKeyDoesNotIncrementTwice() throws Exception {
    UUID tenant = UUID.randomUUID();
    try (Connection app = app()) {
      setTenant(app, tenant);
      long first = record(app, "evt-dup", tenant, DAY, "WORKFLOW_EXECUTION", 1, -1);
      long second = record(app, "evt-dup", tenant, DAY, "WORKFLOW_EXECUTION", 1, -1);

      assertThat(first).isEqualTo(1L);
      assertThat(second).isEqualTo(-2L);
      assertThat(counterValue(app, tenant)).isEqualTo(1L);
      assertThat(eventCount(app, tenant)).isEqualTo(1L);
    }
  }

  @Test
  void oneHundredConcurrentStartsAtTheBoundaryAllowExactlyTheLimit() throws Exception {
    UUID tenant = UUID.randomUUID();
    int attempts = 100;
    long limit = 10L;
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch ready = new CountDownLatch(attempts);
    CountDownLatch start = new CountDownLatch(1);
    ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();

    try {
      for (int i = 0; i < attempts; i++) {
        String key = "concurrent-" + i;
        pool.submit(
            () -> {
              try (Connection app = app()) {
                setTenant(app, tenant);
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                results.add(record(app, key, tenant, DAY, "WORKFLOW_EXECUTION", 1, limit));
              } catch (Exception ex) {
                throw new IllegalStateException(ex);
              }
            });
      }
      assertThat(ready.await(60, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(results).hasSize(attempts);
    assertThat(results.stream().filter(value -> value > 0).count()).isEqualTo(limit);
    assertThat(results.stream().filter(value -> value == -1L).count()).isEqualTo(attempts - limit);
    try (Connection app = app()) {
      setTenant(app, tenant);
      assertThat(counterValue(app, tenant)).isEqualTo(limit);
      // Rejected attempts must not leave ledger rows behind (they would corrupt reconciliation).
      assertThat(eventCount(app, tenant)).isEqualTo(limit);
    }
  }

  @Test
  void rlsHidesOtherTenantsCounters() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    try (Connection app = app()) {
      setTenant(app, tenantA);
      record(app, "rls-a", tenantA, DAY, "WORKFLOW_EXECUTION", 1, -1);
    }
    try (Connection app = app()) {
      setTenant(app, tenantB);
      assertThat(counterValue(app, tenantB)).isZero();
      assertThat(eventCount(app, tenantB)).isZero();
      int updated;
      try (PreparedStatement ps =
          app.prepareStatement("UPDATE usage_counters SET value = 99 WHERE tenant_id = ?")) {
        ps.setObject(1, tenantA);
        updated = ps.executeUpdate();
      }
      assertThat(updated).isZero();
    }
  }

  @Test
  void negativeDeltaIsRejectedByTheDatabase() throws Exception {
    UUID tenant = UUID.randomUUID();
    try (Connection app = app()) {
      setTenant(app, tenant);
      assertThatThrownBy(() -> record(app, "bad", tenant, DAY, "WORKFLOW_EXECUTION", -1, -1))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("usage delta must be positive");
    }
  }

  @Test
  void reconciliationRepairsDriftingCountersAndReportsTheDrift() throws Exception {
    UUID tenant = UUID.randomUUID();
    try (Connection app = app()) {
      setTenant(app, tenant);
      record(app, "rec-1", tenant, DAY, "WORKFLOW_EXECUTION", 2, -1);
      record(app, "rec-2", tenant, DAY, "WORKFLOW_EXECUTION", 3, -1);
    }
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "UPDATE usage_counters SET value = 99 WHERE tenant_id = ? AND day = ?")) {
      ps.setObject(1, tenant);
      ps.setObject(2, DAY);
      ps.executeUpdate();
    }

    List<long[]> drifts = new ArrayList<>();
    try (Connection app = app();
        PreparedStatement ps = app.prepareStatement("SELECT * FROM awap_usage_reconcile(?, ?)")) {
      ps.setObject(1, DAY);
      ps.setObject(2, DAY);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          drifts.add(new long[] {rs.getLong("expected"), rs.getLong("actual")});
        }
      }
      assertThat(counterValue(app, tenant)).isZero(); // RLS without tenant ctx
    }

    assertThat(drifts).hasSize(1);
    assertThat(drifts.get(0)[0]).isEqualTo(5L);
    assertThat(drifts.get(0)[1]).isEqualTo(99L);

    try (Connection app = app()) {
      setTenant(app, tenant);
      assertThat(counterValue(app, tenant)).isEqualTo(5L);
    }

    // A second pass has nothing left to repair.
    try (Connection app = app();
        PreparedStatement ps =
            app.prepareStatement("SELECT count(*) FROM awap_usage_reconcile(?, ?)")) {
      ps.setObject(1, DAY);
      ps.setObject(2, DAY);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isZero();
      }
    }
  }

  @Test
  void archiveMovesOldAndDeletedTenantCountersOutOfTheLiveTable() throws Exception {
    UUID liveTenant = UUID.randomUUID();
    UUID deletedTenant = UUID.randomUUID();
    LocalDate oldDay = DAY.minusDays(500);
    try (Connection admin = admin();
        Statement stmt = admin.createStatement()) {
      stmt.executeUpdate(insertTenant(liveTenant, "live-" + liveTenant, false));
      stmt.executeUpdate(insertTenant(deletedTenant, "deleted-" + deletedTenant, true));
    }

    try (Connection app = app()) {
      setTenant(app, liveTenant);
      record(app, "old-live", liveTenant, oldDay, "WORKFLOW_EXECUTION", 1, -1);
      record(app, "today-live", liveTenant, DAY, "WORKFLOW_EXECUTION", 1, -1);
    }
    try (Connection app = app()) {
      setTenant(app, deletedTenant);
      record(app, "deleted-tenant", deletedTenant, DAY, "AI_CALL", 1, -1);
    }

    try (Connection app = app();
        PreparedStatement ps = app.prepareStatement("SELECT awap_usage_archive(?)")) {
      ps.setObject(1, DAY.minusDays(400));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(2);
      }
    }

    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM usage_counter_archive WHERE tenant_id = ?")) {
      ps.setObject(1, deletedTenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(1L);
      }
    }

    try (Connection app = app()) {
      setTenant(app, liveTenant);
      assertThat(counterOn(app, liveTenant, oldDay)).isZero();
      assertThat(counterOn(app, liveTenant, DAY)).isEqualTo(1L);

      try (PreparedStatement ps =
          app.prepareStatement(
              "SELECT count(*) FROM usage_events WHERE tenant_id = ? AND day = ?")) {
        ps.setObject(1, liveTenant);
        ps.setObject(2, oldDay);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertThat(rs.getLong(1)).isZero();
        }
      }
    }
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection app() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
  }

  private static void setTenant(Connection connection, UUID tenant) throws SQLException {
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + tenant + "'");
    }
  }

  private static long record(
      Connection connection,
      String eventKey,
      UUID tenant,
      LocalDate day,
      String metric,
      long delta,
      long limit)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT awap_usage_record_event(?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, eventKey);
      ps.setObject(2, tenant);
      ps.setObject(3, day);
      ps.setString(4, metric);
      ps.setLong(5, delta);
      ps.setLong(6, limit);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static long counterValue(Connection connection, UUID tenant) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT COALESCE(sum(value), 0) FROM usage_counters WHERE tenant_id = ?")) {
      ps.setObject(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static long counterOn(Connection connection, UUID tenant, LocalDate day)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT COALESCE(sum(value), 0) FROM usage_counters WHERE tenant_id = ? AND day = ?")) {
      ps.setObject(1, tenant);
      ps.setObject(2, day);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static long eventCount(Connection connection, UUID tenant) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT count(*) FROM usage_events WHERE tenant_id = ?")) {
      ps.setObject(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static String insertTenant(UUID id, String slug, boolean deleted) {
    return "INSERT INTO tenants (id, slug, name, plan, max_users, max_workflows,"
        + " max_ai_calls_per_month, active, created_at, updated_at, deleted) VALUES ('"
        + id
        + "', '"
        + slug
        + "', '"
        + slug
        + "', 'FREE', 10, 10, 1000, true, now(), now(), "
        + deleted
        + ")";
  }
}
