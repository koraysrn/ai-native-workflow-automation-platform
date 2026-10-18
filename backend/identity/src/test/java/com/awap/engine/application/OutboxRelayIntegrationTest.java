package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Verifies the outbox relay publishes via KafkaTemplate and marks the event published. */
@SpringBootTest(classes = com.awap.identity.IdentityApplication.class)
class OutboxRelayIntegrationTest {

  @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private OutboxRelayService outboxRelayService;

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

    verify(kafkaTemplate)
        .send("workflow.task.events", event.getAggregateId().toString(), "{\"x\":1}");
  }
}
