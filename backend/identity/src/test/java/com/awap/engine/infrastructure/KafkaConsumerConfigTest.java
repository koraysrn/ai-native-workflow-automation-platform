package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;

class KafkaConsumerConfigTest {

  @Test
  void consumerFactoryConfiguresBoundedPollingAndStartupRetries() {
    ConsumerFactory<String, String> factory =
        new KafkaConsumerConfig().consumerFactory("localhost:9092", "http://localhost:8081", 50);

    Map<String, Object> properties = factory.getConfigurationProperties();
    assertThat(properties)
        .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50)
        .containsEntry(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1_000)
        .containsEntry(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 30_000)
        .containsEntry(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, 1_000)
        .containsEntry(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 5_000)
        .containsEntry(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 5 * 1024 * 1024);
  }

  @Test
  void maxPollRecordsIsNeverZero() {
    Map<String, Object> properties = new HashMap<>();
    KafkaConsumerConfig.configureDeadLetterConsumption(properties, 0);

    assertThat(properties).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
  }

  @Test
  void listenerContainerKeepsRunningWhileKafkaIsUnavailableAtStartup() throws Exception {
    ConsumerFactory<String, String> factory =
        new KafkaConsumerConfig().consumerFactory("localhost:65534", "http://localhost:65534", 50);
    ContainerProperties properties = new ContainerProperties("awap.dlq");
    properties.setGroupId("awap-dlq-startup-retry-test");
    properties.setMessageListener((MessageListener<String, String>) record -> {});
    ConcurrentMessageListenerContainer<String, String> container =
        new ConcurrentMessageListenerContainer<>(factory, properties);

    container.start();
    try {
      assertThat(container.isRunning()).isTrue();
      Thread.sleep(1_000);
      // The container is still running: the consumer retries in the background instead of
      // failing application startup.
      assertThat(container.isRunning()).isTrue();
    } finally {
      container.stop();
    }
    assertThat(container.isRunning()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void commitsTheOffsetOnlyPerRecordAfterTheListenerReturns() {
    KafkaConsumerConfig config = new KafkaConsumerConfig();

    var factory =
        config.kafkaListenerContainerFactory(
            mock(ConsumerFactory.class), mock(KafkaTemplate.class));

    assertThat(factory.getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.RECORD);
  }
}
