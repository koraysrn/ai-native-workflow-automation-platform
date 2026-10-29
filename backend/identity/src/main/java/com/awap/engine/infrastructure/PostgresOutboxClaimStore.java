package com.awap.engine.infrastructure;

import com.awap.engine.application.OutboxClaimStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL adapter for the outbox lease state machine.
 *
 * <p>Every operation goes through a V44 {@code SECURITY DEFINER} function with a pinned {@code
 * search_path}. Those functions run as the migration owner (a superuser in production), so the
 * relay can lease rows of every tenant even though {@code awap_app} itself is constrained by {@code
 * FORCE ROW LEVEL SECURITY}. All lease arithmetic happens on the database clock via {@code now()},
 * so a relay running with a skewed local clock can neither release a fresh claim nor keep a dead
 * one alive.
 *
 * <p>The relay uses the dedicated outbox connection pool, which is why this adapter uses plain JDBC
 * instead of JPA: polling and claiming must never compete with the request-serving pool.
 */
@Repository
@ConditionalOnProperty(
    name = "awap.outbox.datasource.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PostgresOutboxClaimStore implements OutboxClaimStore {

  private static final String CLAIM_SQL =
      "SELECT id, event_type, aggregate_id, payload_json, claim_token"
          + " FROM awap_claim_outbox_batch(?::int, ?::int, ?::int)";

  private static final String MARK_PUBLISHED_SQL =
      "SELECT awap_mark_outbox_published(?::uuid, ?::uuid)";

  private static final String RELEASE_SQL = "SELECT awap_release_outbox_event(?::uuid, ?::uuid)";

  private static final String RELEASE_STALE_SQL =
      "SELECT id, tenant_id, claim_age_seconds"
          + " FROM awap_release_stale_outbox_claims(?::int, ?::int)";

  private static final String LEGACY_SQL =
      "SELECT id, tenant_id, event_type, created_at, publish_attempts"
          + " FROM awap_find_legacy_published_outbox(?::int)";

  private static final String COUNT_EXHAUSTED_SQL = "SELECT awap_count_exhausted_outbox(?::int)";

  private final JdbcTemplate jdbcTemplate;

  public PostgresOutboxClaimStore(@Qualifier("outboxJdbcTemplate") JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<ClaimedOutboxEvent> claimBatch(int limit, Duration lease, int maxAttempts) {
    return jdbcTemplate.query(
        CLAIM_SQL,
        (rs, rowNum) ->
            new ClaimedOutboxEvent(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("payload_json"),
                rs.getObject("claim_token", UUID.class)),
        Math.max(1, limit),
        Math.max(1, (int) lease.toSeconds()),
        Math.max(1, maxAttempts));
  }

  @Override
  public boolean markPublished(UUID id, UUID claimToken) {
    Integer updated =
        jdbcTemplate.queryForObject(MARK_PUBLISHED_SQL, Integer.class, id, claimToken);
    return updated != null && updated > 0;
  }

  @Override
  public boolean release(UUID id, UUID claimToken) {
    Integer updated = jdbcTemplate.queryForObject(RELEASE_SQL, Integer.class, id, claimToken);
    return updated != null && updated > 0;
  }

  @Override
  public List<ReleasedOutboxClaim> releaseStaleClaims(Duration lease, int limit) {
    return jdbcTemplate.query(
        RELEASE_STALE_SQL,
        (rs, rowNum) ->
            new ReleasedOutboxClaim(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getDouble("claim_age_seconds")),
        Math.max(1, (int) lease.toSeconds()),
        Math.max(1, limit));
  }

  @Override
  public List<LegacyOutboxRow> findLegacyPublishedRows(int limit) {
    return jdbcTemplate.query(
        LEGACY_SQL,
        (rs, rowNum) ->
            new LegacyOutboxRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getInt("publish_attempts")),
        Math.max(1, limit));
  }

  @Override
  public long countExhausted(int maxAttempts) {
    Long count =
        jdbcTemplate.queryForObject(COUNT_EXHAUSTED_SQL, Long.class, Math.max(1, maxAttempts));
    return count == null ? 0L : count;
  }
}
