package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Outbox crash window (issue #211): a broker failure between claim and publish releases the lease,
 * and a later relay run publishes the same event exactly once.
 */
@Tag("chaos")
@ExtendWith(MockitoExtension.class)
class OutboxCrashChaosTest {

  private static final Duration LEASE = Duration.ofSeconds(60);

  @Mock private OutboxClaimStore claimStore;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  @SuppressWarnings("unchecked")
  @Mock
  private ObjectProvider<KafkaTemplate<String, String>> provider;

  private OutboxRelayService service(SimpleMeterRegistry registry) {
    return new OutboxRelayService(
        provider, claimStore, new ObjectMapper(), new OutboxMetrics(registry), 100, LEASE, 5);
  }

  private static OutboxClaimStore.ClaimedOutboxEvent event(UUID token) {
    return new OutboxClaimStore.ClaimedOutboxEvent(
        UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), "{\"ok\":true}", token, null);
  }

  @Test
  void aBrokerOutageBetweenClaimAndPublishIsReleasedAndRetriedExactlyOnce() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxRelayService relay = service(registry);
    UUID token = UUID.randomUUID();
    OutboxClaimStore.ClaimedOutboxEvent claimed = event(token);
    when(provider.getIfAvailable()).thenReturn(kafkaTemplate);
    when(claimStore.claimBatch(100, LEASE, 5)).thenReturn(List.of(claimed));
    // The broker is down for the first relay run.
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    relay.relayEvents();

    verify(claimStore).release(claimed.id(), token);
    verify(claimStore, never()).markPublished(any(), any());
    assertThat(registry.find(OutboxMetrics.PUBLISH_FAILURES).counter().count()).isEqualTo(1.0);

    // The reconciliation/reconnect path makes the row claimable again; the second run publishes it.
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
    when(claimStore.markPublished(claimed.id(), token)).thenReturn(true);

    relay.relayEvents();

    verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    verify(claimStore, times(1)).markPublished(claimed.id(), token);
  }

  @Test
  void aClaimThatLostItsLeaseIsNotPublished() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxRelayService relay = service(registry);
    UUID token = UUID.randomUUID();
    OutboxClaimStore.ClaimedOutboxEvent claimed = event(token);
    when(provider.getIfAvailable()).thenReturn(kafkaTemplate);
    when(claimStore.claimBatch(100, LEASE, 5)).thenReturn(List.of(claimed));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
    when(claimStore.markPublished(claimed.id(), token)).thenReturn(false);

    relay.relayEvents();

    // One delivery attempt, zero successful publications: a takeover replica owns the row.
    verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    verify(claimStore, times(1)).markPublished(claimed.id(), token);
  }
}
