package com.awap.engine.infrastructure;

import com.awap.engine.application.OutboxRelayMode;
import com.awap.engine.application.OutboxRelaySource;
import com.awap.engine.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev/test-only relay source used when {@code awap.outbox.datasource.enabled=false} (issue #122).
 *
 * <p>It shares the request-serving pool and reads through Spring Data JPA. That means it runs
 * <strong>without a tenant context</strong>: on PostgreSQL, {@code outbox_events} is protected by
 * {@code FORCE ROW LEVEL SECURITY} and {@code current_setting('awap.tenant_id', true)} is null, so
 * the queries return no rows and the relay silently stops publishing.
 *
 * <p>Because of that the fallback is restricted to single-connection dev/test environments; {@link
 * com.awap.engine.application.OutboxRelayConfigurationGuard} refuses to start with it in {@code
 * prod}/{@code staging}, the {@code outboxRelay} health indicator reports {@code rlsSafe=false},
 * and runbook O8 documents the restriction.
 */
@Component
@ConditionalOnProperty(name = "awap.outbox.datasource.enabled", havingValue = "false")
public class JpaOutboxRelaySource implements OutboxRelaySource {

  private final OutboxEventRepository repository;

  public JpaOutboxRelaySource(OutboxEventRepository repository) {
    this.repository = repository;
  }

  @Override
  public OutboxRelayMode mode() {
    return OutboxRelayMode.JPA;
  }

  @Override
  public List<PendingEvent> claimBatch(int limit) {
    int bounded = Math.max(1, limit);
    return repository.findTop100ByPublishedFalseOrderByCreatedAtAsc().stream()
        .filter(event -> repository.claim(event.getId()) == 1)
        .limit(bounded)
        .map(JpaOutboxRelaySource::toPendingEvent)
        .toList();
  }

  @Override
  public void release(UUID id) {
    repository.release(id);
  }

  private static PendingEvent toPendingEvent(OutboxEvent event) {
    return new PendingEvent(
        event.getId(), event.getEventType(), event.getAggregateId(), event.getPayloadJson());
  }
}
