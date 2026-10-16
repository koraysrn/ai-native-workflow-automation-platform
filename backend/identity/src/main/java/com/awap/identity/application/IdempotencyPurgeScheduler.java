package com.awap.identity.application;

import com.awap.identity.infrastructure.IdempotencyRecordRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes idempotency records older than 24 hours to bound table growth. */
@Service
public class IdempotencyPurgeScheduler {

  private static final long RETENTION_HOURS = 24;

  private final IdempotencyRecordRepository idempotencyRecordRepository;

  public IdempotencyPurgeScheduler(IdempotencyRecordRepository idempotencyRecordRepository) {
    this.idempotencyRecordRepository = idempotencyRecordRepository;
  }

  @Scheduled(fixedDelay = 3_600_000)
  @Transactional
  public void purgeExpired() {
    Instant cutoff = Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS);
    idempotencyRecordRepository.deleteByCreatedAtBefore(cutoff);
  }
}
