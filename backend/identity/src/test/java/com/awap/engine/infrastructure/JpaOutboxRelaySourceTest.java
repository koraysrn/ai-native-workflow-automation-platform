package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxRelayMode;
import com.awap.engine.application.OutboxRelaySource;
import com.awap.engine.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Contract of the dev/test-only JPA fallback adapter (issue #122). */
class JpaOutboxRelaySourceTest {

  private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
  private final JpaOutboxRelaySource source = new JpaOutboxRelaySource(repository);

  private static OutboxEvent event(String eventType) {
    return new OutboxEvent(
        UUID.randomUUID(), "TASK", UUID.randomUUID(), eventType, "{\"ok\":true}");
  }

  @Test
  void reportsTheJpaMode() {
    assertThat(source.mode()).isEqualTo(OutboxRelayMode.JPA);
  }

  @Test
  void claimsOnlyTheRowsThisRelayWon() {
    OutboxEvent mine = event("TASK_COMPLETED");
    OutboxEvent takenByAnotherRelay = event("TASK_COMPLETED");
    when(repository.findTop100ByPublishedFalseOrderByCreatedAtAsc())
        .thenReturn(List.of(mine, takenByAnotherRelay));
    when(repository.claim(mine.getId())).thenReturn(1);
    when(repository.claim(takenByAnotherRelay.getId())).thenReturn(0);

    List<OutboxRelaySource.PendingEvent> claimed = source.claimBatch(100);

    assertThat(claimed)
        .extracting(OutboxRelaySource.PendingEvent::id)
        .containsExactly(mine.getId());
    assertThat(claimed.get(0).eventType()).isEqualTo("TASK_COMPLETED");
    assertThat(claimed.get(0).aggregateId()).isEqualTo(mine.getAggregateId());
    assertThat(claimed.get(0).payloadJson()).isEqualTo("{\"ok\":true}");
  }

  @Test
  void neverClaimsMoreThanTheConfiguredBatch() {
    OutboxEvent first = event("TASK_COMPLETED");
    OutboxEvent second = event("TASK_COMPLETED");
    when(repository.findTop100ByPublishedFalseOrderByCreatedAtAsc())
        .thenReturn(List.of(first, second));
    when(repository.claim(first.getId())).thenReturn(1);
    when(repository.claim(second.getId())).thenReturn(1);

    assertThat(source.claimBatch(1)).hasSize(1);
  }

  @Test
  void releasesTheRowForTheNextRun() {
    UUID id = UUID.randomUUID();

    source.release(id);

    verify(repository).release(id);
  }
}
