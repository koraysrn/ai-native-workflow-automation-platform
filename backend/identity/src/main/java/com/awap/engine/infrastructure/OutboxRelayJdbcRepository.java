package com.awap.engine.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Minimal JDBC access for the relay, bound to the dedicated outbox datasource. It deliberately
 * avoids JPA so the relay never opens a session on the request-serving pool.
 */
@Repository
@ConditionalOnProperty(
    name = "awap.outbox.datasource.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxRelayJdbcRepository {

  private final JdbcTemplate jdbcTemplate;

  public OutboxRelayJdbcRepository(@Qualifier("outboxJdbcTemplate") JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Claims and returns a batch atomically through the SECURITY DEFINER helper, so the relay can
   * read across tenants despite RLS and two relay replicas cannot publish the same row.
   */
  public List<RelayEvent> claimBatch(int limit) {
    return jdbcTemplate.query(
        "SELECT id, event_type, aggregate_id, payload_json FROM awap_claim_outbox_batch(?)",
        (rs, rowNum) ->
            new RelayEvent(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("payload_json")),
        limit);
  }

  public void release(UUID id) {
    jdbcTemplate.queryForObject("SELECT awap_release_outbox_event(?)", Integer.class, id);
  }

  /** Minimal projection of an unpublished outbox row. */
  public record RelayEvent(UUID id, String eventType, UUID aggregateId, String payloadJson) {}
}
