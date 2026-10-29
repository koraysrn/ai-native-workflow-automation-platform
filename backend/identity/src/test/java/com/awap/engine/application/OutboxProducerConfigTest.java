package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Proves the relay's Kafka producer is configured idempotently (issue #121, design item 6) and that
 * the claim lease is longer than the relay's send timeout, which is what prevents a second relay
 * from publishing a row that is still in flight.
 *
 * <p>The context runs against its own in-memory database so the background relay of this
 * Kafka-enabled context cannot touch rows owned by other tests.
 */
@SpringBootTest(
    classes = com.awap.identity.IdentityApplication.class,
    properties = {
      "awap.kafka.enabled=true",
      "spring.datasource.url=jdbc:h2:mem:awap-kafka-producer-test;DB_CLOSE_DELAY=-1"
    })
class OutboxProducerConfigTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  @Value("${awap.outbox.claim-lease:60s}")
  private Duration claimLease;

  @Test
  void kafkaTemplateUsesAnIdempotentProducerWithAcksAll() {
    Map<String, Object> configured =
        new HashMap<>(kafkaTemplate.getProducerFactory().getConfigurationProperties());
    ProducerConfig producerConfig = new ProducerConfig(configured);

    assertThat(producerConfig.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
    // Kafka normalises "all" to "-1" once idempotence is enabled; both mean "every in-sync
    // replica".
    assertThat(producerConfig.getString(ProducerConfig.ACKS_CONFIG)).isIn("all", "-1");
    assertThat(producerConfig.getInt(ProducerConfig.RETRIES_CONFIG)).isEqualTo(Integer.MAX_VALUE);
    assertThat(producerConfig.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION))
        .isEqualTo(5);
  }

  @Test
  void claimLeaseIsLongerThanTheSendTimeout() {
    assertThat(claimLease).isGreaterThan(OutboxRelayService.SEND_TIMEOUT);
  }
}
