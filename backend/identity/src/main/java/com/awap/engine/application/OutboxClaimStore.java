package com.awap.engine.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for the outbox claim/lease state machine.
 *
 * <p>A claim never implies publication: {@link #claimBatch} only takes a lease (identified by a
 * fencing token) and leaves {@code published = false}, so a relay that dies between the claim and
 * the Kafka send leaves a recoverable row behind. Two adapters exist:
 *
 * <ul>
 *   <li>the PostgreSQL adapter backed by the {@code SECURITY DEFINER} helpers from V44, which sees
 *       every tenant through the dedicated outbox connection pool, and
 *   <li>the JPA adapter used when the dedicated pool is disabled (tests/single-connection
 *       deployments).
 * </ul>
 */
public interface OutboxClaimStore {

  /**
   * Leases up to {@code limit} unpublished rows whose claim is free or expired. Rows that already
   * exhausted {@code maxAttempts} are never returned, which is what stops a corrupted payload from
   * being retried forever.
   */
  List<ClaimedOutboxEvent> claimBatch(int limit, Duration lease, int maxAttempts);

  /**
   * Marks the row published only when the caller still holds the lease token and the row is not yet
   * published. Returns {@code false} when the lease was lost (another relay owns the row now).
   */
  boolean markPublished(UUID id, UUID claimToken);

  /**
   * Releases the lease after a failed publish, incrementing the attempt counter. Returns {@code
   * false} when the lease was already lost.
   */
  boolean release(UUID id, UUID claimToken);

  /** Releases claims older than the lease and returns them so the caller can meter each row. */
  List<ReleasedOutboxClaim> releaseStaleClaims(Duration lease, int limit);

  /**
   * Legacy/partial rows: {@code published = true} but no {@code published_at}. Never republished.
   */
  List<LegacyOutboxRow> findLegacyPublishedRows(int limit);

  /** How many unpublished rows reached the attempt limit and need operator intervention. */
  long countExhausted(int maxAttempts);

  /** An outbox row leased by this relay, with the fencing token required to complete it. */
  record ClaimedOutboxEvent(
      UUID id, String eventType, UUID aggregateId, String payloadJson, UUID claimToken) {}

  /** A stale claim that reconciliation released, with the age of the abandoned lease. */
  record ReleasedOutboxClaim(UUID id, UUID tenantId, double claimAgeSeconds) {}

  /** A row left in the pre-V44 "published without a timestamp" state. */
  record LegacyOutboxRow(
      UUID id, UUID tenantId, String eventType, Instant createdAt, int publishAttempts) {}
}
