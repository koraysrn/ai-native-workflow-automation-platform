package com.awap.engine.infrastructure;

import com.awap.engine.application.OutboxClaimStore;
import com.awap.engine.domain.OutboxEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * JPA adapter for the outbox lease state machine, used when the dedicated outbox connection pool is
 * disabled (tests and single-connection deployments).
 *
 * <p>It implements the same contract as {@link PostgresOutboxClaimStore}: the claim is a
 * conditional update that only succeeds while no live lease is held, so two relays working on the
 * same candidate list cannot both publish a row. Unlike the PostgreSQL adapter this path uses the
 * application clock, which errs on the safe side — a relay whose clock runs behind can only claim
 * later, never release a fresh claim.
 */
@Repository
@ConditionalOnProperty(name = "awap.outbox.datasource.enabled", havingValue = "false")
public class JpaOutboxClaimStore implements OutboxClaimStore {

  private final OutboxEventRepository repository;
  private final Clock clock;

  @Autowired
  public JpaOutboxClaimStore(OutboxEventRepository repository) {
    this(repository, Clock.systemUTC());
  }

  /** Visible for tests and for callers that need a deterministic clock (crash simulation). */
  public JpaOutboxClaimStore(OutboxEventRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public List<ClaimedOutboxEvent> claimBatch(int limit, Duration lease, int maxAttempts) {
    Instant now = clock.instant();
    Instant staleBefore = now.minus(lease);
    int attempts = Math.max(1, maxAttempts);
    List<ClaimedOutboxEvent> claimed = new ArrayList<>();
    for (OutboxEvent candidate :
        repository.findClaimable(attempts, Pageable.ofSize(Math.max(1, limit)))) {
      UUID token = UUID.randomUUID();
      if (repository.claim(candidate.getId(), token, now, staleBefore, attempts) == 1) {
        claimed.add(
            new ClaimedOutboxEvent(
                candidate.getId(),
                candidate.getEventType(),
                candidate.getAggregateId(),
                candidate.getPayloadJson(),
                token));
      }
    }
    return claimed;
  }

  @Override
  public boolean markPublished(UUID id, UUID claimToken) {
    return repository.markPublished(id, claimToken, clock.instant()) == 1;
  }

  @Override
  public boolean release(UUID id, UUID claimToken) {
    return repository.releaseClaim(id, claimToken) == 1;
  }

  @Override
  public List<ReleasedOutboxClaim> releaseStaleClaims(Duration lease, int limit) {
    Instant now = clock.instant();
    Instant staleBefore = now.minus(lease);
    List<ReleasedOutboxClaim> released = new ArrayList<>();
    for (OutboxEvent stale :
        repository.findStaleClaims(staleBefore, Pageable.ofSize(Math.max(1, limit)))) {
      Optional<UUID> token = stale.getClaimToken();
      if (token.isEmpty()) {
        continue;
      }
      if (repository.releaseClaim(stale.getId(), token.get()) == 1) {
        double ageSeconds =
            stale
                .getClaimedAt()
                .map(claimedAt -> Duration.between(claimedAt, now).toMillis() / 1000d)
                .orElse(0d);
        released.add(new ReleasedOutboxClaim(stale.getId(), stale.getTenantId(), ageSeconds));
      }
    }
    return released;
  }

  @Override
  public List<LegacyOutboxRow> findLegacyPublishedRows(int limit) {
    return repository.findLegacyPublished(Pageable.ofSize(Math.max(1, limit))).stream()
        .map(
            event ->
                new LegacyOutboxRow(
                    event.getId(),
                    event.getTenantId(),
                    event.getEventType(),
                    event.getCreatedAt(),
                    event.getPublishAttempts()))
        .toList();
  }

  @Override
  public long countExhausted(int maxAttempts) {
    return repository.countExhausted(Math.max(1, maxAttempts));
  }
}
