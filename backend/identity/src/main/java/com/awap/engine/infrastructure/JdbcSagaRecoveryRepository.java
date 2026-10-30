package com.awap.engine.infrastructure;

import com.awap.engine.application.SagaRecoveryCandidate;
import com.awap.engine.application.SagaRecoveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Claims due saga compensations through the {@code awap_claim_saga_compensations} SECURITY DEFINER
 * helper so the scheduler can resume rows of every tenant despite RLS.
 */
@Repository
public class JdbcSagaRecoveryRepository implements SagaRecoveryRepository {

  private static final String CLAIM_SQL =
      "SELECT tenant_id, saga_instance_id" + " FROM awap_claim_saga_compensations(?, ?, ?, ?)";

  private final JdbcTemplate jdbcTemplate;

  public JdbcSagaRecoveryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<SagaRecoveryCandidate> claimDue(
      Instant now, UUID leaseToken, Duration lease, int limit) {
    long leaseSeconds = Math.max(1L, (lease.toMillis() + 999L) / 1000L);
    return jdbcTemplate.query(
        CLAIM_SQL,
        (rs, rowNum) ->
            new SagaRecoveryCandidate(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("saga_instance_id", UUID.class),
                leaseToken),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        leaseToken,
        (int) leaseSeconds,
        Math.max(1, limit));
  }
}
