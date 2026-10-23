package com.awap.engine.application;

import com.awap.engine.infrastructure.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically archives then removes published outbox events older than the configurable retention
 * window, preserving the audit trail instead of hard-deleting it.
 */
@Component
public class OutboxPurgeScheduler {

  private final OutboxEventRepository repository;
  private final OutboxEventArchiver archiver;
  private final Duration retention;

  public OutboxPurgeScheduler(
      OutboxEventRepository repository,
      OutboxEventArchiver archiver,
      @Value("${awap.outbox.retention-days:7}") long retentionDays) {
    this.repository = repository;
    this.archiver = archiver;
    this.retention = Duration.ofDays(retentionDays);
  }

  @Scheduled(cron = "0 0 3 * * *")
  @Transactional
  public void purge() {
    Instant cutoff = Instant.now().minus(retention);
    repository.findPublishedBefore(cutoff).forEach(archiver::archive);
    repository.deletePublishedBefore(cutoff);
  }
}
