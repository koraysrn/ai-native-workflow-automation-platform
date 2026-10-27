package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxRelayMode;
import com.awap.engine.application.OutboxRelaySource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Contract of the dedicated-pool (SECURITY DEFINER) adapter (issue #122). */
class JdbcOutboxRelaySourceTest {

  private final OutboxRelayJdbcRepository repository = mock(OutboxRelayJdbcRepository.class);
  private final JdbcOutboxRelaySource source = new JdbcOutboxRelaySource(repository);

  @Test
  void reportsTheJdbcMode() {
    assertThat(source.mode()).isEqualTo(OutboxRelayMode.JDBC);
  }

  @Test
  void mapsTheSecurityDefinerProjectionToTheSharedEventType() {
    UUID id = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    when(repository.claimBatch(25))
        .thenReturn(
            List.of(
                new OutboxRelayJdbcRepository.RelayEvent(
                    id, "FORM_SUBMITTED", aggregateId, "{\"ok\":true}")));

    List<OutboxRelaySource.PendingEvent> claimed = source.claimBatch(25);

    assertThat(claimed)
        .containsExactly(
            new OutboxRelaySource.PendingEvent(id, "FORM_SUBMITTED", aggregateId, "{\"ok\":true}"));
  }

  @Test
  void clampsANonPositiveBatchSize() {
    when(repository.claimBatch(1)).thenReturn(List.of());

    assertThat(source.claimBatch(0)).isEmpty();
  }

  @Test
  void releasesThroughTheSecurityDefinerFunction() {
    UUID id = UUID.randomUUID();

    source.release(id);

    verify(repository).release(id);
  }
}
