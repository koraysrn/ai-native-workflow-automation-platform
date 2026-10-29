package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.JpaOutboxClaimStore;
import com.awap.engine.infrastructure.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies end-to-end relay behaviour on the JPA fallback path: the claim is a lease, and only a
 * successful Kafka send marks the row published (with a timestamp and no residual claim).
 *
 * <p>The context uses its own in-memory database and pushes the scheduled relay interval out of the
 * way: Spring caches contexts (and their schedulers) for the whole JVM, so a shared database would
 * let another context's relay steal the lease this test is inspecting.
 */
@SpringBootTest(
    classes = com.awap.identity.IdentityApplication.class,
    properties = {
      "awap.outbox.relay-interval-ms=3600000",
      "awap.outbox.relay-initial-delay-ms=3600000",
      "spring.datasource.url=jdbc:h2:mem:awap-outbox-relay-it;DB_CLOSE_DELAY=-1"
    })
class OutboxRelayIntegrationTest {

  @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private OutboxRelayService outboxRelayService;
  @Autowired private OutboxClaimStore outboxClaimStore;

  @Test
  void relayPublishesEventToCorrectTopicAndMarksItPublished() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));

    OutboxEvent event =
        new OutboxEvent(
            UUID.randomUUID(), "WORKFLOW", UUID.randomUUID(), "TASK_COMPLETED", "{\"x\":1}");
    outboxRepository.save(event);

    outboxRelayService.relayEvents();

    OutboxEvent persisted = outboxRepository.findById(event.getId()).orElseThrow();
    assertThat(persisted.isPublished()).isTrue();
    assertThat(persisted.getPublishedAt()).isPresent();
    assertThat(persisted.getClaimToken()).isEmpty();
    assertThat(persisted.getClaimedAt()).isEmpty();
    assertThat(persisted.getPublishAttempts()).isZero();

    verify(kafkaTemplate)
        .send("workflow.task.events", event.getAggregateId().toString(), "{\"x\":1}");
  }

  @Test
  void jpaClaimStoreIsTheActiveAdapterWhenTheDedicatedPoolIsDisabled() {
    assertThat(outboxClaimStore).isInstanceOf(JpaOutboxClaimStore.class);
  }

  @Test
  void crashBetweenClaimAndPublishIsReconciledAndRepublishedExactlyOnce() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    OutboxMetrics metrics = new OutboxMetrics(meterRegistry);

    OutboxEvent event =
        new OutboxEvent(
            UUID.randomUUID(), "WORKFLOW", UUID.randomUUID(), "TASK_COMPLETED", "{\"crash\":1}");
    outboxRepository.save(event);

    // Simulate a relay that claimed the row and then died before publishing: the lease is taken an
    // hour in the past, so it is already expired when reconciliation runs.
    JpaOutboxClaimStore dyingRelay =
        new JpaOutboxClaimStore(
            outboxRepository,
            Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
    OutboxClaimStore.ClaimedOutboxEvent orphaned =
        dyingRelay.claimBatch(1000, Duration.ofSeconds(60), 5).stream()
            .filter(claim -> claim.id().equals(event.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(orphaned.claimToken()).isNotNull();
    OutboxEvent claimed = outboxRepository.findById(event.getId()).orElseThrow();
    assertThat(claimed.isPublished()).isFalse();
    assertThat(claimed.getClaimToken()).isPresent();

    OutboxReconciliationScheduler scheduler =
        new OutboxReconciliationScheduler(
            outboxClaimStore, metrics, Duration.ofSeconds(60), 5, 1000);
    scheduler.reconcile();

    assertThat(
            meterRegistry
                .get(OutboxMetrics.STUCK)
                .tag(OutboxMetrics.REASON_TAG, OutboxMetrics.REASON_STALE_CLAIM)
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1.0d);

    outboxRelayService.relayEvents();

    OutboxEvent republished = outboxRepository.findById(event.getId()).orElseThrow();
    assertThat(republished.isPublished()).isTrue();
    assertThat(republished.getPublishedAt()).isPresent();
    assertThat(republished.getClaimToken()).isEmpty();
    // Exactly one Kafka record for this aggregate: no duplicate publication after the crash.
    verify(kafkaTemplate, times(1))
        .send(
            eq("workflow.task.events"), eq(event.getAggregateId().toString()), eq("{\"crash\":1}"));
  }
}
