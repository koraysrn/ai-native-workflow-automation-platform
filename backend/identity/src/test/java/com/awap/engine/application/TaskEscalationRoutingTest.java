package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Contract tests for the escalation topic mapping (issue #207). */
@ExtendWith(MockitoExtension.class)
class TaskEscalationRoutingTest {

  private static final Duration LEASE = Duration.ofSeconds(60);

  @Mock private OutboxClaimStore claimStore;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  @SuppressWarnings("unchecked")
  @Mock
  private ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;

  @Test
  void taskEscalationDueIsRelayedToItsDedicatedTopic() {
    OutboxRelayService service =
        new OutboxRelayService(
            kafkaTemplateProvider,
            claimStore,
            new ObjectMapper(),
            new OutboxMetrics(new SimpleMeterRegistry()),
            100,
            LEASE,
            5);
    UUID token = UUID.randomUUID();
    OutboxClaimStore.ClaimedOutboxEvent event =
        new OutboxClaimStore.ClaimedOutboxEvent(
            UUID.randomUUID(),
            "TASK_ESCALATION_DUE",
            UUID.randomUUID(),
            "{\"taskId\":\"x\"}",
            token,
            null);
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    when(claimStore.claimBatch(100, LEASE, 5)).thenReturn(List.of(event));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
    when(claimStore.markPublished(event.id(), token)).thenReturn(true);

    service.relayEvents();

    ArgumentCaptor<ProducerRecord<String, String>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(captor.capture());
    assertThat(captor.getValue().topic()).isEqualTo("task.escalation.events");
  }

  @Test
  void theNodeCompletionListenerNoLongerSubscribesToTheEscalationTopic() throws Exception {
    KafkaListener annotation =
        NodeCompletedEventListener.class
            .getMethod("onMessage", String.class)
            .getAnnotation(KafkaListener.class);

    assertThat(Set.of(annotation.topics()))
        .containsExactlyInAnyOrder("workflow.task.events", "workflow.instance.events")
        .doesNotContain("task.escalation.events");
  }
}
