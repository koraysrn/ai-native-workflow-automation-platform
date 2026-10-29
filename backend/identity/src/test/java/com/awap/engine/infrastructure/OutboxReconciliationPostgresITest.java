package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.application.OutboxClaimStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the V44 lease/reconciliation functions against a real PostgreSQL 16 instance.
 *
 * <p>Writes use the container superuser, while every assertion from the relay's point of view runs
 * as the non-owner {@code awap_app} role. That combination proves the {@code SECURITY DEFINER}
 * functions see all tenants even though {@code awap_app} is blocked by {@code FORCE ROW LEVEL
 * SECURITY} when it touches the table directly.
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxReconciliationPostgresITest {

  private static final Duration LEASE = Duration.ofSeconds(60);
  private static final int MAX_ATTEMPTS = 5;
  private static final int BATCH = 10_000;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("awap");

  private static JdbcTemplate admin;
  private static JdbcTemplate relay;
  private static PostgresOutboxClaimStore store;

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
    relay =
        new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "awap_app", "awap_app"));
    store = new PostgresOutboxClaimStore(relay);
  }

  private static UUID insertEvent(UUID tenantId, String eventType, boolean published) {
    UUID id = UUID.randomUUID();
    admin.update(
        "INSERT INTO outbox_events (id, tenant_id, aggregate_type, aggregate_id, event_type,"
            + " payload_json, created_at, published, publish_attempts)"
            + " VALUES (?, ?, 'TASK', ?, ?, '{\"ok\":true}', now(), ?, 0)",
        id,
        tenantId,
        UUID.randomUUID(),
        eventType,
        published);
    return id;
  }

  private static void markCrashedClaim(UUID id, int secondsAgo) {
    admin.update(
        "UPDATE outbox_events SET published = false,"
            + " claimed_at = now() - make_interval(secs => ?), claim_token = gen_random_uuid()"
            + " WHERE id = ?",
        secondsAgo,
        id);
  }

  private static Map<String, Object> row(UUID id) {
    return admin.queryForMap(
        "SELECT published, published_at, claimed_at, claim_token, publish_attempts"
            + " FROM outbox_events WHERE id = ?",
        id);
  }

  private static List<UUID> claimedIds() {
    return store.claimBatch(BATCH, LEASE, MAX_ATTEMPTS).stream()
        .map(OutboxClaimStore.ClaimedOutboxEvent::id)
        .toList();
  }

  private static OutboxClaimStore.ClaimedOutboxEvent claim(UUID id) {
    return store.claimBatch(BATCH, LEASE, MAX_ATTEMPTS).stream()
        .filter(claimed -> claimed.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected the event to be claimed: " + id));
  }

  @Test
  void claimLeasesTheRowWithoutPublishingIt() {
    UUID id = insertEvent(UUID.randomUUID(), "TASK_COMPLETED", false);

    OutboxClaimStore.ClaimedOutboxEvent claimed = claim(id);

    assertThat(claimed.claimToken()).isNotNull();
    Map<String, Object> stored = row(id);
    assertThat(stored.get("published")).isEqualTo(false);
    assertThat(stored.get("published_at")).isNull();
    assertThat(stored.get("claimed_at")).isNotNull();
    assertThat(stored.get("claim_token")).isEqualTo(claimed.claimToken());
    assertThat(stored.get("publish_attempts")).isEqualTo(0);
  }

  @Test
  void secondRelayCannotClaimWhileTheLeaseIsFresh() {
    UUID id = insertEvent(UUID.randomUUID(), "TASK_COMPLETED", false);
    claim(id);

    assertThat(claimedIds()).doesNotContain(id);
  }

  @Test
  void onlyTheTokenHolderMarksTheRowPublishedAndOnlyOnce() {
    UUID id = insertEvent(UUID.randomUUID(), "TASK_COMPLETED", false);
    UUID token = claim(id).claimToken();

    assertThat(store.markPublished(id, UUID.randomUUID())).isFalse();
    assertThat(row(id).get("published")).isEqualTo(false);

    assertThat(store.markPublished(id, token)).isTrue();
    Map<String, Object> published = row(id);
    assertThat(published.get("published")).isEqualTo(true);
    assertThat(published.get("published_at")).isNotNull();
    assertThat(published.get("claimed_at")).isNull();
    assertThat(published.get("claim_token")).isNull();

    assertThat(store.markPublished(id, token)).isFalse();
  }

  @Test
  void failedPublishReleasesTheClaimAndIncrementsAttempts() {
    UUID id = insertEvent(UUID.randomUUID(), "TASK_COMPLETED", false);
    UUID token = claim(id).claimToken();

    assertThat(store.release(id, token)).isTrue();
    Map<String, Object> released = row(id);
    assertThat(released.get("published")).isEqualTo(false);
    assertThat(released.get("claimed_at")).isNull();
    assertThat(released.get("claim_token")).isNull();
    assertThat(released.get("publish_attempts")).isEqualTo(1);
    assertThat(store.release(id, token)).isFalse();
  }

  @Test
  void claimOfADeadRelayIsReleasedAfterTheLeaseAndRepublishedExactlyOnce() {
    UUID id = insertEvent(UUID.randomUUID(), "TASK_COMPLETED", false);
    markCrashedClaim(id, 600);

    List<OutboxClaimStore.ReleasedOutboxClaim> released = store.releaseStaleClaims(LEASE, BATCH);
    OutboxClaimStore.ReleasedOutboxClaim mine =
        released.stream()
            .filter(claim -> claim.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("stale claim was not released: " + id));
    assertThat(mine.claimAgeSeconds()).isGreaterThanOrEqualTo(600d);

    // The same stale claim is never released twice.
    assertThat(store.releaseStaleClaims(LEASE, BATCH))
        .extracting(OutboxClaimStore.ReleasedOutboxClaim::id)
        .doesNotContain(id);

    OutboxClaimStore.ClaimedOutboxEvent reclaimed = claim(id);
    assertThat(store.markPublished(id, reclaimed.claimToken())).isTrue();
    assertThat(row(id).get("published")).isEqualTo(true);
  }

  @Test
  void freshClaimIsNotReleasedWhileTheRelayIsStillInsideTheSendTimeout() {
    UUID id = insertEvent(UUID.randomUUID(), "TASK_COMPLETED", false);
    UUID token = claim(id).claimToken();

    assertThat(store.releaseStaleClaims(LEASE, BATCH))
        .extracting(OutboxClaimStore.ReleasedOutboxClaim::id)
        .doesNotContain(id);
    assertThat(row(id).get("claim_token")).isEqualTo(token);
  }

  @Test
  void legacyPublishedRowIsReportedButNeverRepublished() {
    UUID id = insertEvent(UUID.randomUUID(), "TASK_COMPLETED", true);
    assertThat(row(id).get("published_at")).isNull();

    assertThat(store.findLegacyPublishedRows(BATCH))
        .extracting(OutboxClaimStore.LegacyOutboxRow::id)
        .contains(id);
    assertThat(claimedIds()).doesNotContain(id);
  }

  @Test
  void securityDefinerFunctionsSeeEveryTenantWhileRlsHidesTheRowsFromTheRelayRole() {
    UUID firstTenant = UUID.randomUUID();
    UUID secondTenant = UUID.randomUUID();
    UUID first = insertEvent(firstTenant, "TASK_COMPLETED", false);
    UUID second = insertEvent(secondTenant, "TASK_COMPLETED", false);

    // awap_app has no tenant context: direct table access is blocked by FORCE RLS ...
    assertThat(relay.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isZero();
    // ... but the SECURITY DEFINER claim sees both tenants.
    assertThat(claimedIds()).contains(first, second);
  }

  @Test
  void rowsThatExhaustedTheirAttemptsAreQuarantined() {
    UUID id = insertEvent(UUID.randomUUID(), "TASK_COMPLETED", false);
    admin.update("UPDATE outbox_events SET publish_attempts = ? WHERE id = ?", MAX_ATTEMPTS, id);

    assertThat(claimedIds()).doesNotContain(id);
    assertThat(store.countExhausted(MAX_ATTEMPTS)).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void reconciliationIndexFromV44Exists() {
    Long indexes =
        admin.queryForObject(
            "SELECT count(*) FROM pg_indexes"
                + " WHERE tablename = 'outbox_events'"
                + " AND indexname = 'idx_outbox_events_reconciliation'",
            Long.class);
    assertThat(indexes).isEqualTo(1L);
  }

  /**
   * Load gate from the issue: 10k stuck rows must be reconciled with a p95 below 10 seconds. The
   * measured value is printed so the PR can record the real bound instead of a guess.
   */
  @Test
  void tenThousandStuckRowsReconcileWithP95BelowTenSeconds() {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO outbox_events (id, tenant_id, aggregate_type, aggregate_id, event_type,"
            + " payload_json, created_at, published, claimed_at, claim_token, publish_attempts)"
            + " SELECT gen_random_uuid(), ?, 'TASK', gen_random_uuid(), 'TASK_COMPLETED', '{}',"
            + " now(), false, now() - interval '10 minutes', gen_random_uuid(), 0"
            + " FROM generate_series(1, 10000)",
        tenantId);
    assertThat(
            admin.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ?", Long.class, tenantId))
        .isEqualTo(10_000L);

    List<Long> durationsMillis = new ArrayList<>();
    for (int run = 0; run < 5; run++) {
      // Re-stale the same pool so every run measures a full 10k-row reconciliation.
      admin.update(
          "UPDATE outbox_events SET claimed_at = now() - interval '10 minutes',"
              + " claim_token = gen_random_uuid()"
              + " WHERE tenant_id = ? AND published = false",
          tenantId);
      long startedAt = System.nanoTime();
      List<OutboxClaimStore.ReleasedOutboxClaim> released = store.releaseStaleClaims(LEASE, BATCH);
      durationsMillis.add((System.nanoTime() - startedAt) / 1_000_000L);
      assertThat(released.stream().filter(claim -> tenantId.equals(claim.tenantId())).count())
          .isEqualTo(10_000L);
    }

    long[] sorted = durationsMillis.stream().mapToLong(Long::longValue).sorted().toArray();
    long p95 = sorted[(int) Math.ceil(0.95d * sorted.length) - 1];
    System.out.printf(
        "Outbox reconciliation of 10k stuck rows: p95=%dms runs=%s%n",
        p95, Arrays.toString(sorted));
    assertThat(p95).isLessThan(10_000L);
  }
}
