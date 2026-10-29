package com.awap.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the notification read state against real PostgreSQL: the conditional (idempotent)
 * transitions, tenant isolation through row-level security, the supporting indexes and the two
 * retention windows (issue #126).
 *
 * <p>The runtime assertions run as the non-owner {@code awap_app} role, so the RLS policy from V35
 * is exercised instead of the container superuser's bypass. Docker-less environments skip the test
 * via {@code disabledWithoutDocker = true}.
 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationReadStateITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static final Instant NOW = Instant.now();

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @Test
  void readTransitionIsConditionalAndIdempotent() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID id = insertDelivery(tenant, NOW.minus(Duration.ofDays(2)), null);

    try (Connection app = appConnection(tenant);
        Statement stmt = app.createStatement()) {
      assertThat(markRead(stmt, tenant, id)).isEqualTo(1);
      OffsetDateTime firstReadAt = readAt(stmt, id);

      assertThat(markRead(stmt, tenant, id)).isZero();
      assertThat(readAt(stmt, id)).isEqualTo(firstReadAt);
    }
  }

  @Test
  void unreadTransitionRestoresTheUnreadStateOnce() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID id = insertDelivery(tenant, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)));

    try (Connection app = appConnection(tenant);
        Statement stmt = app.createStatement()) {
      assertThat(markUnread(stmt, tenant, id)).isEqualTo(1);
      assertThat(readAt(stmt, id)).isNull();
      assertThat(markUnread(stmt, tenant, id)).isZero();
    }
  }

  @Test
  void crossTenantReadStateIsBlocked() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID foreign = insertDelivery(tenantB, NOW.minus(Duration.ofDays(2)), null);

    try (Connection app = appConnection(tenantA);
        Statement stmt = app.createStatement()) {
      assertThat(markRead(stmt, tenantA, foreign)).isZero();
      assertThat(
              countRows(
                  stmt,
                  "SELECT count(*) FROM notification_deliveries WHERE id = '" + foreign + "'"))
          .isZero();
    }

    try (Connection app = appConnection(tenantB);
        Statement stmt = app.createStatement()) {
      assertThat(readAt(stmt, foreign)).isNull();
    }
  }

  @Test
  void unreadCountMatchesTheUnreadListing() throws Exception {
    UUID tenant = UUID.randomUUID();
    insertDelivery(tenant, NOW.minus(Duration.ofDays(3)), null);
    insertDelivery(tenant, NOW.minus(Duration.ofDays(2)), null);
    insertDelivery(tenant, NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofHours(5)));

    try (Connection app = appConnection(tenant);
        Statement stmt = app.createStatement()) {
      long unread =
          countRows(stmt, "SELECT count(*) FROM notification_deliveries WHERE read_at IS NULL");
      long listed =
          countRows(
              stmt,
              "SELECT count(*) FROM (SELECT id FROM notification_deliveries"
                  + " WHERE read_at IS NULL ORDER BY created_at DESC) AS inbox");

      assertThat(unread).isEqualTo(2);
      assertThat(listed).isEqualTo(unread);
    }
  }

  @Test
  void readRetentionPurgeLeavesUnreadAndRecentReadStateIntact() throws Exception {
    UUID tenant = UUID.randomUUID();
    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.execute("TRUNCATE notification_deliveries");
    }

    UUID readOld =
        insertDelivery(tenant, NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(40)));
    UUID readRecent =
        insertDelivery(tenant, NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(1)));
    UUID unreadOld = insertDelivery(tenant, NOW.minus(Duration.ofDays(200)), null);
    UUID unreadRecent = insertDelivery(tenant, NOW.minus(Duration.ofDays(2)), null);

    try (Connection app = appConnection(tenant)) {
      assertThat(purgeRead(app, NOW.minus(Duration.ofDays(30)))).isEqualTo(1);

      try (Statement stmt = app.createStatement()) {
        assertThat(exists(stmt, readOld)).isFalse();
        assertThat(exists(stmt, readRecent)).isTrue();
        assertThat(exists(stmt, unreadOld)).isTrue();
        assertThat(exists(stmt, unreadRecent)).isTrue();
        assertThat(readAt(stmt, readRecent)).isNotNull();
      }

      assertThat(purgeUnread(app, NOW.minus(Duration.ofDays(180)))).isEqualTo(1);

      try (Statement stmt = app.createStatement()) {
        assertThat(exists(stmt, unreadOld)).isFalse();
        assertThat(exists(stmt, unreadRecent)).isTrue();
        assertThat(readAt(stmt, readRecent)).isNotNull();
        assertThat(markRead(stmt, tenant, unreadRecent)).isEqualTo(1);
      }
    }
  }

  @Test
  void readStateIndexesExist() throws Exception {
    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'notification_deliveries'")) {
      List<String> indexes = new ArrayList<>();
      while (rs.next()) {
        indexes.add(rs.getString(1));
      }
      assertThat(indexes)
          .contains(
              "idx_notification_deliveries_tenant_read",
              "idx_notification_deliveries_tenant_unread",
              "idx_notification_deliveries_tenant_created");
    }
  }

  @Test
  void largeUnreadCountUsesThePartialIndex() throws Exception {
    UUID tenant = UUID.randomUUID();
    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.execute(
          "INSERT INTO notification_deliveries"
              + " (id, tenant_id, dedupe_key, channel, recipient, template_key, status, attempts,"
              + " created_at, updated_at)"
              + " SELECT gen_random_uuid(), '"
              + tenant
              + "', 'bulk-' || g, 'WEB', 'bulk@example.com', 'welcome', 'SENT', 1, now(), now()"
              + " FROM generate_series(1, 2000) AS g");
    }

    try (Connection app = appConnection(tenant);
        Statement stmt = app.createStatement()) {
      stmt.execute("SET enable_seqscan = off");
      List<String> plan = new ArrayList<>();
      try (ResultSet rs =
          stmt.executeQuery(
              "EXPLAIN SELECT count(*) FROM notification_deliveries"
                  + " WHERE tenant_id = '"
                  + tenant
                  + "' AND read_at IS NULL")) {
        while (rs.next()) {
          plan.add(rs.getString(1));
        }
      }

      assertThat(
              countRows(stmt, "SELECT count(*) FROM notification_deliveries WHERE read_at IS NULL"))
          .isEqualTo(2000);
      assertThat(String.join("\n", plan)).contains("idx_notification_deliveries_tenant_unread");
    }
  }

  private static int markRead(Statement stmt, UUID tenant, UUID id) throws Exception {
    return stmt.executeUpdate(
        "UPDATE notification_deliveries SET read_at = now(), updated_at = now()"
            + " WHERE id = '"
            + id
            + "' AND tenant_id = '"
            + tenant
            + "' AND read_at IS NULL");
  }

  private static int markUnread(Statement stmt, UUID tenant, UUID id) throws Exception {
    return stmt.executeUpdate(
        "UPDATE notification_deliveries SET read_at = NULL, updated_at = now()"
            + " WHERE id = '"
            + id
            + "' AND tenant_id = '"
            + tenant
            + "' AND read_at IS NOT NULL");
  }

  private static OffsetDateTime readAt(Statement stmt, UUID id) throws Exception {
    try (ResultSet rs =
        stmt.executeQuery("SELECT read_at FROM notification_deliveries WHERE id = '" + id + "'")) {
      return rs.next() ? rs.getObject(1, OffsetDateTime.class) : null;
    }
  }

  private static boolean exists(Statement stmt, UUID id) throws Exception {
    return countRows(stmt, "SELECT count(*) FROM notification_deliveries WHERE id = '" + id + "'")
        == 1;
  }

  private static long countRows(Statement stmt, String sql) throws Exception {
    try (ResultSet rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static int purgeRead(Connection connection, Instant cutoff) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT awap_purge_read_notifications(?)")) {
      statement.setObject(1, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static int purgeUnread(Connection connection, Instant cutoff) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT awap_purge_unread_notifications(?)")) {
      statement.setObject(1, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static UUID insertDelivery(UUID tenant, Instant createdAt, Instant readAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection admin = adminConnection();
        PreparedStatement statement =
            admin.prepareStatement(
                "INSERT INTO notification_deliveries"
                    + " (id, tenant_id, dedupe_key, channel, recipient, template_key, status,"
                    + " attempts, created_at, updated_at, read_at)"
                    + " VALUES (?, ?, ?, 'WEB', 'user@example.com', 'welcome', 'SENT', 1, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, tenant);
      statement.setString(3, "dedupe-" + id);
      statement.setObject(4, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
      statement.setObject(5, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
      statement.setObject(
          6, readAt == null ? null : OffsetDateTime.ofInstant(readAt, ZoneOffset.UTC));
      statement.executeUpdate();
    }
    return id;
  }

  private static Connection adminConnection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection appConnection(UUID tenant) throws Exception {
    Connection connection =
        DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("SET awap.tenant_id = '" + tenant + "'");
    }
    return connection;
  }
}
