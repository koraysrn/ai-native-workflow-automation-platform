package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.engine.application.OutboxRelayConfigurationGuard;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the RLS behaviour the JPA outbox fallback depends on, against a real PostgreSQL 16 with
 * row-level security enabled (issue #122, test plan 6.4 and 7.4).
 *
 * <p>Every assertion runs as the non-owner runtime role {@code awap_app} (created by V12), exactly
 * like the application. The fallback issues the predicates shown here — a {@code SELECT} on {@code
 * outbox_events WHERE published = false} followed by an {@code UPDATE ... WHERE id = ? AND
 * published = false} — on the request-serving pool, i.e. without a tenant context:
 *
 * <ul>
 *   <li>without a tenant context RLS filters every row, so nothing is claimable and the relay would
 *       stop silently (this is why prod/staging refuse to start with the fallback),
 *   <li>inside a tenant-scoped connection only that tenant's row is visible and claimable, so no
 *       row of another tenant can leak into a publish,
 *   <li>the {@code SECURITY DEFINER} function used by the dedicated pool sees both tenants, which
 *       is the only path that is safe in production.
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxFallbackRlsPostgresIT {

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static JdbcTemplate admin;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  private static Connection appConnection() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
  }

  private static UUID insertUnpublished(UUID tenantId) {
    UUID id = UUID.randomUUID();
    admin.update(
        "INSERT INTO outbox_events (id, tenant_id, aggregate_type, aggregate_id, event_type,"
            + " payload_json, created_at, published)"
            + " VALUES (?, ?, 'TASK', ?, 'TASK_COMPLETED', '{\"ok\":true}', now(), false)",
        id,
        tenantId,
        UUID.randomUUID());
    return id;
  }

  private static long visibleRows(Connection connection, UUID id) throws Exception {
    try (Statement stmt = connection.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT count(*) FROM outbox_events WHERE id = '" + id + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static int claim(Connection connection, UUID id) throws Exception {
    try (Statement stmt = connection.createStatement()) {
      return stmt.executeUpdate(
          "UPDATE outbox_events SET published = true WHERE id = '"
              + id
              + "' AND published = false");
    }
  }

  private static boolean isPublished(UUID id) {
    return Boolean.TRUE.equals(
        admin.queryForObject(
            "SELECT published FROM outbox_events WHERE id = ?", Boolean.class, id));
  }

  @Test
  void theFallbackWithoutATenantContextSeesNoRows() throws Exception {
    UUID id = insertUnpublished(TENANT_A);

    try (Connection app = appConnection()) {
      // This is the query the JPA fallback runs; current_setting('awap.tenant_id', true) is null,
      // so the V13/V21 policies filter the row out even though it exists.
      assertThat(visibleRows(app, id)).isZero();
      assertThat(claim(app, id)).isZero();
      // A second, fresh session behaves the same: nothing leaks out of the tenant scope.
      try (Connection secondSession = appConnection()) {
        assertThat(visibleRows(secondSession, id)).isZero();
      }
    }

    assertThat(isPublished(id)).isFalse();
  }

  @Test
  void aTenantScopedConnectionClaimsAndPublishesOnlyThatTenantRow() throws Exception {
    UUID mine = insertUnpublished(TENANT_A);
    UUID other = insertUnpublished(TENANT_B);

    try (Connection app = appConnection();
        Statement stmt = app.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + TENANT_A + "'");

      assertThat(visibleRows(app, mine)).isEqualTo(1L);
      assertThat(visibleRows(app, other)).isZero();
      assertThat(claim(app, mine)).isEqualTo(1);
      // Cross-tenant negative: the policy makes the other tenant's row invisible to the update.
      assertThat(claim(app, other)).isZero();
    }

    assertThat(isPublished(mine)).isTrue();
    assertThat(isPublished(other)).isFalse();
  }

  @Test
  void theSecurityDefinerClaimUsedByTheDedicatedPoolSeesEveryTenant() throws Exception {
    UUID mine = insertUnpublished(TENANT_A);
    UUID other = insertUnpublished(TENANT_B);

    List<UUID> claimed = new ArrayList<>();
    try (Connection app = appConnection();
        Statement stmt = app.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT id FROM awap_claim_outbox_batch(1000, 60, 5)")) {
      while (rs.next()) {
        claimed.add(rs.getObject("id", UUID.class));
      }
    }

    assertThat(claimed).contains(mine, other);
    // Claiming only takes the lease; the row stays unpublished until the broker acknowledged it.
    assertThat(isPublished(mine)).isFalse();
    assertThat(isPublished(other)).isFalse();
  }

  @Test
  void theGuardRefusesTheFallbackInProductionInsteadOfStoppingSilently() {
    MockEnvironment production = new MockEnvironment();
    production.setActiveProfiles("prod");

    assertThatThrownBy(
            () -> new OutboxRelayConfigurationGuard(production, "false", "awap-outbox-relay"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("row-level security")
        .hasMessageContaining("prod");
  }
}
