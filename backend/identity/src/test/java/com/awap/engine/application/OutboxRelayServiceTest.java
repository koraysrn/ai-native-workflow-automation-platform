package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

  private static final Duration LEASE = Duration.ofSeconds(60);
  private static final int MAX_ATTEMPTS = 5;

  @Mock private OutboxClaimStore claimStore;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  @SuppressWarnings("unchecked")
  @Mock
  private ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;

  private SimpleMeterRegistry meterRegistry;
  private OutboxRelayService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service =
        new OutboxRelayService(
            kafkaTemplateProvider,
            claimStore,
            new ObjectMapper(),
            new OutboxMetrics(meterRegistry),
            100,
            LEASE,
            MAX_ATTEMPTS);
  }

  private static CompletableFuture<SendResult<String, String>> sent() {
    return CompletableFuture.completedFuture(new SendResult<>(null, null));
  }

  private static OutboxClaimStore.ClaimedOutboxEvent claimed(
      String eventType, String payload, UUID aggregateId, UUID token) {
    return new OutboxClaimStore.ClaimedOutboxEvent(
        UUID.randomUUID(), eventType, aggregateId, payload, token);
  }

  @Test
  void claimsPublishesWithAggregateIdKeyAndMarksPublished() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    UUID aggregateId = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    OutboxClaimStore.ClaimedOutboxEvent event =
        claimed("TASK_COMPLETED", "{\"ok\":true}", aggregateId, token);
    when(claimStore.claimBatch(100, LEASE, MAX_ATTEMPTS)).thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());
    when(claimStore.markPublished(event.id(), token)).thenReturn(true);

    service.relayEvents();

    verify(kafkaTemplate).send("workflow.task.events", aggregateId.toString(), "{\"ok\":true}");
    verify(claimStore).markPublished(event.id(), token);
    verify(claimStore, never()).release(any(), any());
    assertThat(meterRegistry.find(OutboxMetrics.PUBLISH_FAILURES).counters()).isEmpty();
  }

  @Test
  void failedSendReleasesClaimAndIncrementsAttempts() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxClaimStore.ClaimedOutboxEvent event =
        claimed("FORM_SUBMITTED", "{\"ok\":true}", UUID.randomUUID(), UUID.randomUUID());
    when(claimStore.claimBatch(100, LEASE, MAX_ATTEMPTS)).thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

    service.relayEvents();

    verify(claimStore).release(event.id(), event.claimToken());
    verify(claimStore, never()).markPublished(any(), any());
    assertThat(
            meterRegistry
                .get(OutboxMetrics.PUBLISH_FAILURES)
                .tag(OutboxMetrics.REASON_TAG, OutboxMetrics.REASON_SEND_FAILED)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void corruptedPayloadIsReleasedWithoutBeingSent() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxClaimStore.ClaimedOutboxEvent event =
        claimed("TASK_COMPLETED", "{not-json", UUID.randomUUID(), UUID.randomUUID());
    when(claimStore.claimBatch(100, LEASE, MAX_ATTEMPTS)).thenReturn(List.of(event));

    service.relayEvents();

    verifyNoInteractions(kafkaTemplate);
    verify(claimStore).release(event.id(), event.claimToken());
    verify(claimStore, never()).markPublished(any(), any());
    assertThat(
            meterRegistry
                .get(OutboxMetrics.PUBLISH_FAILURES)
                .tag(OutboxMetrics.REASON_TAG, OutboxMetrics.REASON_CORRUPTED_PAYLOAD)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void lostLeaseOnMarkIsReportedAndNeverReleasesSomeoneElsesClaim() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxClaimStore.ClaimedOutboxEvent event =
        claimed("TASK_COMPLETED", "{\"ok\":true}", UUID.randomUUID(), UUID.randomUUID());
    when(claimStore.claimBatch(100, LEASE, MAX_ATTEMPTS)).thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());
    when(claimStore.markPublished(event.id(), event.claimToken())).thenReturn(false);

    service.relayEvents();

    verify(claimStore, never()).release(any(), any());
    assertThat(
            meterRegistry
                .get(OutboxMetrics.PUBLISH_FAILURES)
                .tag(OutboxMetrics.REASON_TAG, OutboxMetrics.REASON_CLAIM_LOST)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void twoRelaysWithTheSameLeaseCannotBothPublishTheRow() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxClaimStore.ClaimedOutboxEvent event =
        claimed("TASK_COMPLETED", "{\"ok\":true}", UUID.randomUUID(), UUID.randomUUID());
    // First relay wins the lease, the second one gets an empty batch for the same row.
    when(claimStore.claimBatch(100, LEASE, MAX_ATTEMPTS))
        .thenReturn(List.of(event))
        .thenReturn(List.of());
    when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());
    when(claimStore.markPublished(event.id(), event.claimToken())).thenReturn(true);

    service.relayEvents();
    service.relayEvents();

    verify(claimStore, times(2)).claimBatch(100, LEASE, MAX_ATTEMPTS);
    verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
    verify(claimStore, times(1)).markPublished(event.id(), event.claimToken());
  }

  @Test
  void relaysNothingWhenNoKafkaTemplateIsConfigured() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(null);

    service.relayEvents();

    verifyNoInteractions(claimStore, kafkaTemplate);
  }

  @Test
  void claimFailureIsContainedSoTheNextRunCanRetry() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    when(claimStore.claimBatch(anyInt(), any(), anyInt()))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatCode(service::relayEvents).doesNotThrowAnyException();

    verifyNoInteractions(kafkaTemplate);
  }

  @Test
  void interruptedSendReleasesTheClaimAndRestoresTheInterruptFlag() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxClaimStore.ClaimedOutboxEvent event =
        claimed("TASK_COMPLETED", "{\"ok\":true}", UUID.randomUUID(), UUID.randomUUID());
    when(claimStore.claimBatch(100, LEASE, MAX_ATTEMPTS)).thenReturn(List.of(event));
    CompletableFuture<SendResult<String, String>> neverCompletes = new CompletableFuture<>();
    when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(neverCompletes);

    Thread.currentThread().interrupt();
    service.relayEvents();

    assertThat(Thread.interrupted()).isTrue();
    verify(claimStore).release(event.id(), event.claimToken());
  }

  @Test
  void rejectsALeaseThatIsNotLongerThanTheSendTimeout() {
    assertThatThrownBy(
            () ->
                new OutboxRelayService(
                    kafkaTemplateProvider,
                    claimStore,
                    new ObjectMapper(),
                    new OutboxMetrics(meterRegistry),
                    100,
                    Duration.ofSeconds(5),
                    MAX_ATTEMPTS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awap.outbox.claim-lease");
  }
}
