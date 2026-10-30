package com.awap.engine.application;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resumes compensations that were interrupted by a crash or are waiting for their retry backoff.
 *
 * <p>Claiming happens through a {@code SECURITY DEFINER} function so the scheduler can see every
 * tenant while RLS stays in force for the application role. Each claimed saga is processed inside a
 * tenant-scoped transaction, and the lease token makes a concurrent replica's late write a no-op.
 */
@Component
@ConditionalOnProperty(
    name = "awap.saga.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SagaCompensationScheduler {

  private static final Logger log = LoggerFactory.getLogger(SagaCompensationScheduler.class);

  private final SagaRecoveryRepository recoveryRepository;
  private final SagaService sagaService;
  private final KafkaTenantContext tenantContext;
  private final Duration lease;
  private final int batchSize;

  @Autowired
  public SagaCompensationScheduler(
      SagaRecoveryRepository recoveryRepository,
      SagaService sagaService,
      KafkaTenantContext tenantContext,
      @Value("${awap.saga.lease-seconds:30}") long leaseSeconds,
      @Value("${awap.saga.batch-size:25}") int batchSize) {
    this(
        recoveryRepository,
        sagaService,
        tenantContext,
        Duration.ofSeconds(Math.max(1L, leaseSeconds)),
        batchSize);
  }

  SagaCompensationScheduler(
      SagaRecoveryRepository recoveryRepository,
      SagaService sagaService,
      KafkaTenantContext tenantContext,
      Duration lease,
      int batchSize) {
    this.recoveryRepository = recoveryRepository;
    this.sagaService = sagaService;
    this.tenantContext = tenantContext;
    this.lease = lease;
    this.batchSize = Math.max(1, batchSize);
  }

  @Scheduled(fixedDelayString = "${awap.saga.scheduler-interval-ms:5000}")
  public void processDueCompensations() {
    runOnce(Instant.now());
  }

  /** Processes one claimed batch; returns the number of sagas claimed by this replica. */
  public int runOnce(Instant now) {
    UUID leaseToken = UUID.randomUUID();
    List<SagaRecoveryCandidate> claimed;
    try {
      claimed = recoveryRepository.claimDue(now, leaseToken, lease, batchSize);
    } catch (RuntimeException ex) {
      log.error("Could not claim due saga compensations; the next tick will retry.", ex);
      return 0;
    }
    for (SagaRecoveryCandidate candidate : claimed) {
      try {
        tenantContext.run(
            candidate.tenantId(),
            () ->
                sagaService.resumeCompensation(
                    candidate.tenantId(), candidate.sagaInstanceId(), candidate.leaseToken()));
      } catch (RuntimeException ex) {
        // One broken compensation must not block the rest of the batch. The lease expires and the
        // saga is claimed again by the next tick or another replica.
        log.error("Saga compensation {} failed while resuming.", candidate.sagaInstanceId(), ex);
      }
    }
    return claimed.size();
  }
}
