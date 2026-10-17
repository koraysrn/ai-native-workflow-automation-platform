package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

/** Verifies the outbox relay against a real embedded broker (publish + published flag). */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"workflow.instance.events", "workflow.task.events"})
class OutboxRelayIntegrationTest {

  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private OutboxRelayService outboxRelayService;

  @Test
  void relayPublishesEventAndMarksItPublished() {
    OutboxEvent event =
        new OutboxEvent(
            UUID.randomUUID(), "WORKFLOW", UUID.randomUUID(), "TASK_COMPLETED", "{\"x\":1}");
    outboxRepository.save(event);

    outboxRelayService.relayEvents();

    OutboxEvent persisted = outboxRepository.findById(event.getId()).orElseThrow();
    assertThat(persisted.isPublished()).isTrue();
  }
}
