package com.awap.engine.infrastructure;

import com.awap.engine.application.OutboxRelayMode;
import com.awap.engine.application.OutboxRelaySource;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default relay source: the dedicated outbox pool plus the V37 {@code SECURITY DEFINER}
 * claim/release functions (issue #122).
 *
 * <p>The functions run as the migration owner and with a pinned {@code search_path}, so the relay
 * can lease rows of every tenant even though the runtime role {@code awap_app} is constrained by
 * {@code FORCE ROW LEVEL SECURITY}. This is the only relay path that is safe in production.
 */
@Component
@ConditionalOnProperty(
    name = "awap.outbox.datasource.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class JdbcOutboxRelaySource implements OutboxRelaySource {

  private final OutboxRelayJdbcRepository repository;

  public JdbcOutboxRelaySource(OutboxRelayJdbcRepository repository) {
    this.repository = repository;
  }

  @Override
  public OutboxRelayMode mode() {
    return OutboxRelayMode.JDBC;
  }

  @Override
  public List<PendingEvent> claimBatch(int limit) {
    return repository.claimBatch(Math.max(1, limit)).stream()
        .map(
            event ->
                new PendingEvent(
                    event.id(), event.eventType(), event.aggregateId(), event.payloadJson()))
        .toList();
  }

  @Override
  public void release(UUID id) {
    repository.release(id);
  }
}
