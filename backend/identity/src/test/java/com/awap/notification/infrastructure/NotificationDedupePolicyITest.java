package com.awap.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL proof for V43: the active-window unique rule, the archival trigger at the TTL
 * boundary, concurrent inserts, purge alignment, tenant validation and insert-versus-purge safety.
 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationDedupePolicyITest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
  private static final Duration WINDOW = Duration.ofHours(1);

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

  @Test
  void migrationAddsTheExpiryColumnAndTheDedupeTrigger() {
    String dataType =
        admin.queryForObject(
            "SELECT data_type FROM information_schema.columns"
                + " WHERE table_name = 'notification_deliveries' AND column_name = 'dedupe_expires_at'",
            String.class);
    Boolean notNull =
        admin.queryForObject(
            "SELECT is_nullable = 'NO' FROM information_schema.columns"
                + " WHERE table_name = 'notification_deliveries' AND column_name = 'dedupe_expires_at'",
            Boolean.class);
    Boolean trigger =
        admin.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_notification_dedupe_guard')",
            Boolean.class);

    assertThat(dataType).isEqualTo("timestamp with time zone");
    assertThat(notNull).isTrue();
    assertThat(trigger).isTrue();
  }

  @Test
  void duplicateInsideTheActiveWindowIsRejectedByTheUniqueConstraint() {
    UUID tenant = createTenant(false);
    insert("k-active", tenant, "SENT", NOW, NOW.plus(WINDOW));

    assertThatThrownBy(() -> insert("k-active", tenant, "SENT", NOW, NOW.plus(WINDOW)))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(count(tenant, "k-active")).isEqualTo(1);
  }

  @Test
  void expiredTerminalRowIsArchivedAndANewDeliveryIsAdmitted() {
    UUID tenant = createTenant(false);
    insert("k-expired", tenant, "SENT", NOW.minus(Duration.ofHours(2)), NOW.minus(WINDOW));

    insert("k-expired", tenant, "SENT", NOW, NOW.plus(WINDOW));

    assertThat(count(tenant, "k-expired")).isEqualTo(1);
    assertThat(countLike(tenant, "k-expired%")).isEqualTo(2);
  }

  @Test
  void twoRequestsExactlyAtTheTtlBoundaryStillCreateOneRow() {
    UUID tenant = createTenant(false);
    Instant expiresAt = NOW.plus(WINDOW);
    insert("k-boundary", tenant, "SENT", NOW, expiresAt);

    assertThatThrownBy(
            () -> insert("k-boundary", tenant, "SENT", expiresAt, expiresAt.plus(WINDOW)))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(count(tenant, "k-boundary")).isEqualTo(1);
  }

  @Test
  void fiftyConcurrentInsertsProduceExactlyOneRow() throws Exception {
    UUID tenant = createTenant(false);
    int attempts = 50;
    AtomicInteger succeeded = new AtomicInteger();
    List<Throwable> unexpected = new CopyOnWriteArrayList<>();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    try {
      for (int i = 0; i < attempts; i++) {
        pool.execute(
            () -> {
              try {
                start.await();
                insert("k-race", tenant, "SENT", NOW, NOW.plus(WINDOW));
                succeeded.incrementAndGet();
              } catch (DuplicateKeyException expected) {
                // exactly one winner
              } catch (Throwable other) {
                unexpected.add(other);
              }
            });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(unexpected).isEmpty();
    assertThat(succeeded.get()).isEqualTo(1);
    assertThat(count(tenant, "k-race")).isEqualTo(1);
  }

  @Test
  void theSameKeyForDifferentTenantsCreatesTwoRows() {
    UUID tenantA = createTenant(false);
    UUID tenantB = createTenant(false);

    insert("k-shared", tenantA, "SENT", NOW, NOW.plus(WINDOW));
    insert("k-shared", tenantB, "SENT", NOW, NOW.plus(WINDOW));

    assertThat(count(tenantA, "k-shared")).isEqualTo(1);
    assertThat(count(tenantB, "k-shared")).isEqualTo(1);
  }

  @Test
  void deletedAndMissingTenantsAreRejectedAtInsert() {
    UUID deletedTenant = createTenant(true);

    assertThatThrownBy(() -> insert("k-deleted", deletedTenant, "SENT", NOW, NOW.plus(WINDOW)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("missing or deleted");
    assertThatThrownBy(() -> insert("k-missing", UUID.randomUUID(), "SENT", NOW, NOW.plus(WINDOW)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("missing or deleted");
  }

  @Test
  void insertDuringPurgeDoesNotDeadlock() throws Exception {
    UUID tenant = createTenant(false);
    for (int i = 0; i < 100; i++) {
      insert(
          "purge-" + i,
          tenant,
          "SENT",
          NOW.minus(Duration.ofDays(40)),
          NOW.minus(Duration.ofDays(40)));
    }

    List<Throwable> failures = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      for (int worker = 0; worker < 2; worker++) {
        pool.execute(
            () -> {
              try {
                start.await();
                for (int i = 0; i < 20; i++) {
                  purge(NOW);
                }
              } catch (Throwable ex) {
                failures.add(ex);
              }
            });
      }
      for (int worker = 0; worker < 6; worker++) {
        int offset = worker * 20;
        pool.execute(
            () -> {
              try {
                start.await();
                for (int i = 0; i < 20; i++) {
                  insert("fresh-" + (offset + i), tenant, "SENT", NOW, NOW.plus(WINDOW));
                }
              } catch (Throwable ex) {
                failures.add(ex);
              }
            });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures).isEmpty();
  }

  @Test
  void purgeNeverDeletesARowWhoseDedupeWindowIsOpen() {
    UUID tenant = createTenant(false);
    insert("k-open-window", tenant, "SENT", NOW.minus(Duration.ofDays(40)), NOW.plus(WINDOW));

    purge(NOW);

    assertThat(count(tenant, "k-open-window")).isEqualTo(1);
  }

  private static void purge(Instant now) {
    admin.queryForObject(
        "SELECT awap_purge_notification_deliveries(?, ?)",
        Integer.class,
        OffsetDateTime.ofInstant(now.minus(Duration.ofDays(30)), ZoneOffset.UTC),
        OffsetDateTime.ofInstant(now.minus(Duration.ofDays(7)), ZoneOffset.UTC));
  }

  private static UUID createTenant(boolean deleted) {
    UUID id = UUID.randomUUID();
    admin.update(
        "INSERT INTO tenants (id, slug, name, plan, max_users, max_workflows,"
            + " max_ai_calls_per_month, active, created_at, updated_at, deleted)"
            + " VALUES (?, ?, 'Acme', 'FREE', 10, 10, 1000, true, ?, ?, ?)",
        id,
        id.toString(),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        deleted);
    return id;
  }

  private static void insert(
      String key, UUID tenant, String status, Instant createdAt, Instant expiresAt) {
    admin.update(
        "INSERT INTO notification_deliveries"
            + " (id, tenant_id, dedupe_key, channel, recipient, template_key, status, attempts,"
            + " created_at, updated_at, dedupe_expires_at)"
            + " VALUES (?, ?, ?, 'EMAIL', 'a@example.com', 'welcome', ?, 1, ?, ?, ?)",
        UUID.randomUUID(),
        tenant,
        key,
        status,
        OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
  }

  private static long count(UUID tenant, String key) {
    Long value =
        admin.queryForObject(
            "SELECT count(*) FROM notification_deliveries WHERE tenant_id = ? AND dedupe_key = ?",
            Long.class,
            tenant,
            key);
    return value == null ? 0 : value;
  }

  private static long countLike(UUID tenant, String pattern) {
    Long value =
        admin.queryForObject(
            "SELECT count(*) FROM notification_deliveries WHERE tenant_id = ? AND dedupe_key LIKE ?",
            Long.class,
            tenant,
            pattern);
    return value == null ? 0 : value;
  }
}
