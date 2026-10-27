package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Verifies the relay loop: one shared path for both modes, with honest failure accounting for the
 * health indicator and the {@code awap.outbox.relay.mode} metric (issue #122).
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

  private static final int BATCH = 100;

  @Mock private OutboxRelaySource source;
  @Mock private ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private SimpleMeterRegistry meterRegistry;
  private OutboxRelayStatus status;
  private OutboxRelayService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    status = new OutboxRelayStatus();
    service =
        new OutboxRelayService(
            source,
            kafkaTemplateProvider,
            status,
            new OutboxRelayMetrics(meterRegistry, OutboxRelayMode.JDBC),
            BATCH);
  }

  private static CompletableFuture<SendResult<String, String>> sent() {
    return CompletableFuture.completedFuture(new SendResult<>(null, null));
  }

  private double modeGauge(OutboxRelayMode mode) {
    return meterRegistry
        .get(OutboxRelayMetrics.RELAY_MODE)
        .tag(OutboxRelayMetrics.MODE_TAG, mode.tag())
        .gauge()
        .value();
  }

  private double failures(OutboxRelayMode mode, String reason) {
    return meterRegistry
        .get(OutboxRelayMetrics.PUBLISH_FAILURES)
        .tag(OutboxRelayMetrics.MODE_TAG, mode.tag())
        .tag(OutboxRelayMetrics.REASON_TAG, reason)
        .counter()
        .count();
  }

  private static OutboxRelaySource.PendingEvent pending(String eventType, UUID aggregateId) {
    return new OutboxRelaySource.PendingEvent(
        UUID.randomUUID(), eventType, aggregateId, "{\"ok\":true}");
  }

  @Test
  void publishesClaimedEventsAndRecordsASuccessfulRun() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxRelaySource.PendingEvent event = pending("TASK_COMPLETED", UUID.randomUUID());
    when(source.claimBatch(BATCH)).thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());

    service.relayEvents();

    verify(kafkaTemplate)
        .send("workflow.task.events", event.aggregateId().toString(), "{\"ok\":true}");
    verify(source, never()).release(any());
    OutboxRelayStatus.Snapshot snapshot = status.snapshot();
    assertThat(snapshot.healthy()).isTrue();
    assertThat(snapshot.publishedEvents()).isEqualTo(1L);
    assertThat(snapshot.lastSuccessfulPublishAt()).isPresent();
    assertThat(modeGauge(OutboxRelayMode.JDBC)).isEqualTo(1.0d);
    assertThat(modeGauge(OutboxRelayMode.JPA)).isZero();
    assertThat(failures(OutboxRelayMode.JDBC, "send_failed")).isZero();
    assertThat(failures(OutboxRelayMode.JDBC, "claim_failed")).isZero();
  }

  @Test
  void releasesTheClaimAndRecordsAFailureWhenTheSendFails() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxRelaySource.PendingEvent event = pending("FORM_SUBMITTED", UUID.randomUUID());
    when(source.claimBatch(BATCH)).thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

    service.relayEvents();

    verify(source).release(event.id());
    OutboxRelayStatus.Snapshot snapshot = status.snapshot();
    assertThat(snapshot.healthy()).isFalse();
    assertThat(snapshot.consecutiveFailures()).isEqualTo(1);
    assertThat(snapshot.lastFailureReason()).contains("send_failed");
    assertThat(snapshot.lastSuccessfulPublishAt()).isEmpty();
    assertThat(failures(OutboxRelayMode.JDBC, "send_failed")).isEqualTo(1.0d);
  }

  @Test
  void claimFailureIsRecordedSoTheNextRunCanRetry() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    when(source.mode()).thenReturn(OutboxRelayMode.JDBC);
    when(source.claimBatch(anyInt())).thenThrow(new IllegalStateException("database unavailable"));

    service.relayEvents();

    verifyNoInteractions(kafkaTemplate);
    OutboxRelayStatus.Snapshot snapshot = status.snapshot();
    assertThat(snapshot.consecutiveFailures()).isEqualTo(1);
    assertThat(snapshot.lastFailureReason()).contains("claim_failed");
    assertThat(failures(OutboxRelayMode.JDBC, "claim_failed")).isEqualTo(1.0d);
  }

  @Test
  void aSuccessfulRunClearsTheFailureAndReportsUpAgain() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxRelaySource.PendingEvent event = pending("TASK_COMPLETED", UUID.randomUUID());
    when(source.claimBatch(BATCH)).thenReturn(List.of(event)).thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
        .thenReturn(sent());

    service.relayEvents();
    assertThat(status.snapshot().healthy()).isFalse();

    service.relayEvents();

    OutboxRelayStatus.Snapshot recovered = status.snapshot();
    assertThat(recovered.healthy()).isTrue();
    assertThat(recovered.consecutiveFailures()).isZero();
    assertThat(recovered.lastSuccessfulPublishAt()).isPresent();
  }

  @Test
  void relaysNothingWhenNoKafkaTemplateIsConfigured() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(null);

    service.relayEvents();

    verifyNoInteractions(source, kafkaTemplate);
    assertThat(status.snapshot().hasRun()).isFalse();
  }

  @Test
  void mapsEveryEventTypeToItsSpecifiedTopic() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    List<OutboxRelaySource.PendingEvent> events =
        List.of(
            pending("TASK_COMPLETED", UUID.randomUUID()),
            pending("TASK_ESCALATION_DUE", UUID.randomUUID()),
            pending("FORM_SUBMITTED", UUID.randomUUID()),
            pending("SUB_PROCESS_COMPLETED", UUID.randomUUID()),
            pending("ANALYTICS_EVENT", UUID.randomUUID()),
            pending("NOTIFICATION_REQUESTED", UUID.randomUUID()),
            pending("DOCUMENT_REGISTERED", UUID.randomUUID()),
            pending("organization.company.created", UUID.randomUUID()),
            pending("SOMETHING_ELSE", UUID.randomUUID()));
    when(source.claimBatch(BATCH)).thenReturn(events);
    when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());

    service.relayEvents();

    assertThat(events)
        .allSatisfy(
            event -> {
              String topic =
                  switch (event.eventType()) {
                    case "TASK_COMPLETED", "TASK_ESCALATION_DUE" -> "workflow.task.events";
                    case "FORM_SUBMITTED" -> "workflow.instance.events";
                    case "SUB_PROCESS_COMPLETED" -> "workflow.subprocess.events";
                    case "ANALYTICS_EVENT" -> "analytics.events";
                    case "NOTIFICATION_REQUESTED" -> "notification.send";
                    case "DOCUMENT_REGISTERED" -> "document.events";
                    case "organization.company.created" -> "organization.events";
                    default -> "workflow.instance.events";
                  };
              verify(kafkaTemplate)
                  .send(topic, event.aggregateId().toString(), event.payloadJson());
            });
    assertThat(status.snapshot().publishedEvents()).isEqualTo(events.size());
  }

  @Test
  void aSendThatThrowsSynchronouslyIsReleasedAndMetered() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxRelaySource.PendingEvent event = pending("TASK_COMPLETED", UUID.randomUUID());
    when(source.claimBatch(BATCH)).thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("producer closed"));

    service.relayEvents();

    verify(source).release(event.id());
    assertThat(status.snapshot().consecutiveFailures()).isEqualTo(1);
    assertThat(failures(OutboxRelayMode.JDBC, "send_failed")).isEqualTo(1.0d);
  }

  @Test
  void timedOutSendReleasesTheClaim() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    OutboxRelaySource.PendingEvent event = pending("TASK_COMPLETED", UUID.randomUUID());
    when(source.claimBatch(BATCH)).thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException("send timeout")));

    service.relayEvents();

    verify(source).release(event.id());
    assertThat(failures(OutboxRelayMode.JDBC, "send_failed")).isEqualTo(1.0d);
  }
}
