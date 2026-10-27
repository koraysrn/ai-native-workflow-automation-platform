package com.awap.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL proof for the V42 retry scheduler primitives: the claim function's {@code FOR
 * UPDATE SKIP LOCKED} + lease semantics, the per-tenant cap, cross-replica concurrency, RLS
 * isolation and the retention purge.
 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationRetryClaimITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  private static JdbcTemplate app;
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
    app =
        new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "awap_app", "awap_app"));
  }

  @Test
  void claimsDueRowsAndSetsTheLease() {
    UUID tenant = UUID.randomUUID();
    UUID id = insertDelivery(tenant, "due-1", "RETRYING", NOW, NOW, NOW.minusSeconds(5));

    List<Map<String, Object>> claimed = claim(NOW, UUID.randomUUID(), 120, 10, 10);

    assertThat(claimed).extracting(row -> row.get("id")).containsExactly(id);
    Map<String, Object> leaseRow =
        admin.queryForMap(
            "SELECT lease_token, lease_until FROM notification_deliveries WHERE id = ?", id);
    assertThat(leaseRow.get("lease_token")).isNotNull();
    assertThat(leaseRow.get("lease_until")).isNotNull();
  }

  @Test
  void doesNotClaimARowUnderAValidLease() {
    UUID tenant = UUID.randomUUID();
    insertDelivery(tenant, "leased-1", "RETRYING", NOW, NOW, NOW.minusSeconds(5));
    claim(NOW, UUID.randomUUID(), 120, 10, 10);

    assertThat(claim(NOW, UUID.randomUUID(), 120, 10, 10)).isEmpty();
  }

  @Test
  void takesOverAnExpiredLease() {
    UUID tenant = UUID.randomUUID();
    UUID id = insertDelivery(tenant, "expired-1", "RETRYING", NOW, NOW, NOW.minusSeconds(5));
    claim(NOW, UUID.randomUUID(), 120, 10, 10);
    admin.update(
        "UPDATE notification_deliveries SET lease_until = ? WHERE id = ?",
        OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC),
        id);

    List<Map<String, Object>> takenOver = claim(NOW, UUID.randomUUID(), 120, 10, 10);

    assertThat(takenOver).extracting(row -> row.get("id")).containsExactly(id);
  }

  @Test
  void skipsSentAndFailedRows() {
    UUID tenant = UUID.randomUUID();
    insertDelivery(tenant, "sent-1", "SENT", NOW, NOW, NOW.minusSeconds(5));
    insertDelivery(tenant, "failed-1", "FAILED", NOW, NOW, NOW.minusSeconds(5));

    assertThat(claim(NOW, UUID.randomUUID(), 120, 10, 10)).isEmpty();
  }

  @Test
  void skipsRowsScheduledInTheFuture() {
    UUID tenant = UUID.randomUUID();
    insertDelivery(tenant, "future-1", "RETRYING", NOW, NOW, NOW.plusSeconds(60));

    assertThat(claim(NOW, UUID.randomUUID(), 120, 10, 10)).isEmpty();
  }

  @Test
  void capsHowManyRowsOneTenantCanTakePerBatch() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    for (int i = 0; i < 10; i++) {
      insertDelivery(tenantA, "fair-a-" + i, "PENDING", NOW, NOW, NOW.minusSeconds(5));
    }
    for (int i = 0; i < 2; i++) {
      insertDelivery(tenantB, "fair-b-" + i, "PENDING", NOW, NOW, NOW.minusSeconds(5));
    }

    List<Map<String, Object>> claimed = claim(NOW, UUID.randomUUID(), 120, 100, 2);

    assertThat(claimed).hasSize(4);
    assertThat(claimed.stream().filter(row -> tenantA.equals(row.get("tenant_id"))).count())
        .isEqualTo(2);
    assertThat(claimed.stream().filter(row -> tenantB.equals(row.get("tenant_id"))).count())
        .isEqualTo(2);
  }

  @Test
  void twentyReplicasClaimOneHundredRowsExactlyOnce() throws Exception {
    for (int i = 0; i < 100; i++) {
      insertDelivery(UUID.randomUUID(), "race-" + i, "PENDING", NOW, NOW, NOW.minusSeconds(5));
    }

    Set<UUID> claimedIds = ConcurrentHashMap.newKeySet();
    AtomicInteger duplicates = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(20);
    try {
      for (int replica = 0; replica < 20; replica++) {
        pool.execute(
            () -> {
              while (true) {
                List<Map<String, Object>> batch = claim(NOW, UUID.randomUUID(), 120, 10, 1000);
                if (batch.isEmpty()) {
                  return;
                }
                for (Map<String, Object> row : batch) {
                  if (!claimedIds.add((UUID) row.get("id"))) {
                    duplicates.incrementAndGet();
                  }
                }
              }
            });
      }
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(duplicates.get()).isZero();
    assertThat(claimedIds).hasSize(100);
  }

  @Test
  void purgeEnforcesTerminalAndRetryRetention() {
    UUID tenant = UUID.randomUUID();
    UUID oldSent =
        insertDelivery(
            tenant,
            "purge-old-sent",
            "SENT",
            NOW.minus(Duration.ofDays(40)),
            NOW.minus(Duration.ofDays(40)),
            null);
    UUID oldFailed =
        insertDelivery(
            tenant,
            "purge-old-failed",
            "FAILED",
            NOW.minus(Duration.ofDays(40)),
            NOW.minus(Duration.ofDays(40)),
            null);
    UUID oldPending =
        insertDelivery(
            tenant,
            "purge-old-pending",
            "PENDING",
            NOW.minus(Duration.ofDays(10)),
            NOW.minus(Duration.ofDays(10)),
            NOW.minus(Duration.ofDays(10)));
    UUID freshSent = insertDelivery(tenant, "purge-fresh-sent", "SENT", NOW, NOW, null);

    Integer purged =
        app.queryForObject(
            "SELECT awap_purge_notification_deliveries(?, ?)",
            Integer.class,
            OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(30)), ZoneOffset.UTC),
            OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(7)), ZoneOffset.UTC));

    assertThat(purged).isEqualTo(3);
    assertThat(exists(oldSent)).isFalse();
    assertThat(exists(oldFailed)).isFalse();
    assertThat(exists(oldPending)).isFalse();
    assertThat(exists(freshSent)).isTrue();
  }

  @Test
  void runtimeRoleStaysTenantScopedWhileTheClaimIsCrossTenant() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    insertDelivery(tenantA, "rls-a", "PENDING", NOW, NOW, NOW.minusSeconds(5));
    insertDelivery(tenantB, "rls-b", "PENDING", NOW, NOW, NOW.minusSeconds(5));

    try (Connection connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), "awap_app", "awap_app");
        Statement statement = connection.createStatement()) {
      statement.execute("SET awap.tenant_id = '" + tenantA + "'");
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT count(*) FROM notification_deliveries WHERE dedupe_key LIKE 'rls-%'")) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
    }

    // The SECURITY DEFINER claim is the only cross-tenant path, and it returns both rows.
    List<Map<String, Object>> claimed = claim(NOW, UUID.randomUUID(), 120, 10, 10);
    assertThat(claimed).hasSize(2);
    assertThat(claimed)
        .extracting(row -> row.get("tenant_id"))
        .containsExactlyInAnyOrder(tenantA, tenantB);
  }

  private static List<Map<String, Object>> claim(
      Instant now, UUID leaseToken, int leaseSeconds, int limit, int perTenantLimit) {
    return app.queryForList(
        "SELECT id, tenant_id FROM awap_claim_notification_retries(?, ?, ?, ?, ?)",
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        leaseToken,
        leaseSeconds,
        limit,
        perTenantLimit);
  }

  private static UUID insertDelivery(
      UUID tenantId,
      String dedupeKey,
      String status,
      Instant createdAt,
      Instant updatedAt,
      Instant nextAttemptAt) {
    UUID id = UUID.randomUUID();
    admin.update(
        "INSERT INTO notification_deliveries"
            + " (id, tenant_id, dedupe_key, channel, recipient, template_key, status, attempts,"
            + " created_at, updated_at, locale, next_attempt_at, version)"
            + " VALUES (?, ?, ?, 'EMAIL', 'a@example.com', 'welcome', ?, 1, ?, ?, 'en', ?, 0)",
        id,
        tenantId,
        dedupeKey,
        status,
        OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC),
        nextAttemptAt == null ? null : OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC));
    return id;
  }

  private static boolean exists(UUID id) {
    Boolean present =
        admin.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM notification_deliveries WHERE id = ?)",
            Boolean.class,
            id);
    return Boolean.TRUE.equals(present);
  }
}
