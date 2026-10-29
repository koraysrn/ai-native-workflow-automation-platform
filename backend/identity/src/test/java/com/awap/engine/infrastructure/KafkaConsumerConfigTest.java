package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;

class KafkaConsumerConfigTest {

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
