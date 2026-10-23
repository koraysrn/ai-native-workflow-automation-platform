package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.awap.engine.infrastructure.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the purge scheduler deletes published events older than the retention window. */
@ExtendWith(MockitoExtension.class)
class OutboxPurgeSchedulerTest {

  @Mock private OutboxEventRepository repository;

  @Test
  void purgesEventsOlderThanSevenDays() {
    OutboxPurgeScheduler scheduler = new OutboxPurgeScheduler(repository);
    Instant before = Instant.now();

    scheduler.purge();

    ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
    verify(repository).deletePublishedBefore(captor.capture());
    Instant cutoff = captor.getValue();
    assertThat(Duration.between(cutoff, before)).isBetween(Duration.ofDays(6), Duration.ofDays(8));
  }
}
