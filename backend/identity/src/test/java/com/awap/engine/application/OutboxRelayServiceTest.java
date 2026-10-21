package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
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

  @Mock private OutboxEventRepository outboxRepository;
  @Mock private ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private OutboxRelayService service;

  @BeforeEach
  void setUp() {
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    service = new OutboxRelayService(outboxRepository, kafkaTemplateProvider);
  }

  @Test
  void relayClaimsPublishesAndKeepsClaimOnSuccess() {
    OutboxEvent event =
        new OutboxEvent(
            UUID.randomUUID(), "TASK", UUID.randomUUID(), "TASK_COMPLETED", "{\"ok\":true}");
    when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
    when(outboxRepository.claim(event.getId())).thenReturn(1);
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));

    service.relayEvents();

    verify(outboxRepository).claim(event.getId());
    verify(outboxRepository, never()).release(any());
    verify(kafkaTemplate)
        .send("workflow.task.events", event.getAggregateId().toString(), "{\"ok\":true}");
  }

  @Test
  void relaySkipsEventsAlreadyClaimedByAnotherRun() {
    OutboxEvent event =
        new OutboxEvent(
            UUID.randomUUID(), "FORM", UUID.randomUUID(), "FORM_SUBMITTED", "{\"ok\":true}");
    when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
    when(outboxRepository.claim(event.getId())).thenReturn(0);

    service.relayEvents();

    verify(outboxRepository).claim(event.getId());
    verifyNoInteractions(kafkaTemplate);
  }

  @Test
  void relayReleasesClaimOnSendFailure() {
    OutboxEvent event =
        new OutboxEvent(
            UUID.randomUUID(), "FORM", UUID.randomUUID(), "FORM_SUBMITTED", "{\"ok\":true}");
    when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
    when(outboxRepository.claim(event.getId())).thenReturn(1);
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

    service.relayEvents();

    verify(outboxRepository).release(event.getId());
    assertThat(event.isPublished()).isFalse();
  }
}
