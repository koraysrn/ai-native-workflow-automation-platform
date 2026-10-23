package com.awap.engine.application;

import com.awap.engine.infrastructure.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Periodically removes published outbox events older than the retention window. */
@Component
public class OutboxPurgeScheduler {

  private static final Duration RETENTION = Duration.ofDays(7);

  private final OutboxEventRepository repository;

  public OutboxPurgeScheduler(OutboxEventRepository repository) {
    this.repository = repository;
  }

  @Scheduled(cron = "0 0 3 * * *")
  @Transactional
  public void purge() {
    repository.deletePublishedBefore(Instant.now().minus(RETENTION));
  }
}
