package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the purge scheduler archives before deleting expired published events. */
@ExtendWith(MockitoExtension.class)
class OutboxPurgeSchedulerTest {

  @Mock private OutboxEventRepository repository;
  @Mock private OutboxEventArchiver archiver;

  @Test
  void purgesEventsOlderThanRetentionWindow() {
    OutboxPurgeScheduler scheduler = new OutboxPurgeScheduler(repository, archiver, 7);
    Instant before = Instant.now();

    scheduler.purge();

    ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
    verify(repository).deletePublishedBefore(captor.capture());
    Instant cutoff = captor.getValue();
    assertThat(Duration.between(cutoff, before)).isBetween(Duration.ofDays(6), Duration.ofDays(8));
  }

  @Test
  void archivesEventsBeforeDeletingThem() {
    OutboxPurgeScheduler scheduler = new OutboxPurgeScheduler(repository, archiver, 7);
    OutboxEvent event =
        new OutboxEvent(UUID.randomUUID(), "TASK", UUID.randomUUID(), "TASK_COMPLETED", "{}");
    event.markPublished();
    when(repository.findPublishedBefore(any(Instant.class))).thenReturn(List.of(event));

    scheduler.purge();

    verify(archiver).archive(event);
    verify(repository).deletePublishedBefore(any(Instant.class));
  }

  @Test
  void deletesWithoutArchivingWhenNothingExpired() {
    OutboxPurgeScheduler scheduler = new OutboxPurgeScheduler(repository, archiver, 7);
    when(repository.findPublishedBefore(any(Instant.class))).thenReturn(List.of());

    scheduler.purge();

    verify(archiver, never()).archive(any());
    verify(repository).deletePublishedBefore(any(Instant.class));
  }
}
