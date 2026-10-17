package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/** Verifies the outbox relay publishes to a real embedded broker and marks the event published. */
@SpringBootTest(classes = com.awap.identity.IdentityApplication.class)
@EmbeddedKafka(partitions = 1, topics = "workflow.task.events")
class OutboxRelayIntegrationTest {

  @Autowired private EmbeddedKafkaBroker embeddedKafka;
  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private OutboxRelayService outboxRelayService;

  @Test
  void relayPublishesEventToBrokerAndMarksItPublished() {
    OutboxEvent event =
        new OutboxEvent(
            UUID.randomUUID(), "WORKFLOW", UUID.randomUUID(), "TASK_COMPLETED", "{\"x\":1}");
    outboxRepository.save(event);

    outboxRelayService.relayEvents();

    OutboxEvent persisted = outboxRepository.findById(event.getId()).orElseThrow();
    assertThat(persisted.isPublished()).isTrue();

    Map<String, Object> consumerProps =
        KafkaTestUtils.consumerProps("outbox-test", "true", embeddedKafka);
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
      embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "workflow.task.events");
      ConsumerRecord<String, String> record =
          KafkaTestUtils.getSingleRecord(consumer, "workflow.task.events");
      assertThat(record.key()).isEqualTo(event.getAggregateId().toString());
      assertThat(record.value()).isEqualTo("{\"x\":1}");
    }
  }
}
