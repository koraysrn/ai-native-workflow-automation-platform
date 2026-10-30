package com.awap.engine.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cross-tenant claim port for the compensation recovery scheduler.
 *
 * <p>The database adapter uses a {@code SECURITY DEFINER} function with {@code FOR UPDATE SKIP
 * LOCKED}, because the runtime role is confined by RLS and a scheduler has no tenant context.
 */
public interface SagaRecoveryRepository {

  /**
   * Claims due sagas, leases them to {@code leaseToken} and returns the claimed rows.
   *
   * @param now current UTC instant
   * @param leaseToken unique token owned by this replica for this batch
   * @param lease how long the claim stays exclusive before another replica may take it over
   * @param limit maximum number of sagas to claim
   */
  List<SagaRecoveryCandidate> claimDue(Instant now, UUID leaseToken, Duration lease, int limit);
}
