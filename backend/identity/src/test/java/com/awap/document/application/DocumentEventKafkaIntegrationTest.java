package com.awap.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-broker verification for issue #208: the {@code document.events} consumer applies a lifecycle
 * fact through the production listener, and a malformed record is dead-lettered to {@code
 * document.events.dlq}.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentEventKafkaIntegrationTest {

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.0"));

  // A per-instance topic pair keeps the shared broker clean across the two test methods while
  // still exercising a real consumer container and the per-topic DLQ recoverer.
  private final String topic = "document.events.it." + UUID.randomUUID();
  private final String dlq = topic + ".dlq";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DocumentService service = mock(DocumentService.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CountDownLatch applied = new CountDownLatch(1);

  private KafkaTemplate<String, String> template;
  private ConcurrentMessageListenerContainer<String, String> container;
  private DocumentEventListener listener;

  @BeforeEach
  void setUp() throws Exception {
    KafkaTenantContext tenantContext = mock(KafkaTenantContext.class);
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(1)).run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());
    listener = new DocumentEventListener(objectMapper, service, tenantContext, registry);
    when(service.applyEvent(any()))
        .thenAnswer(
            invocation -> {
              applied.countDown();
              return Optional.empty();
            });

    createTopics();
    template = new KafkaTemplate<>(producerFactory());

    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

    ContainerProperties containerProperties = new ContainerProperties(topic);
    containerProperties.setGroupId("document-event-it-" + UUID.randomUUID());
    containerProperties.setAckMode(ContainerProperties.AckMode.RECORD);
    container =
        new ConcurrentMessageListenerContainer<>(
            new DefaultKafkaConsumerFactory<>(props), containerProperties);
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, ex) -> new TopicPartition(record.topic() + ".dlq", record.partition()));
    // No retries: a poison record reaches the DLQ immediately, which is what the test asserts.
    container.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L)));
    container.setupMessageListener(
        (MessageListener<String, String>) record -> listener.onMessage(record.value()));
    container.start();
  }

  @AfterEach
  void tearDown() {
    if (container != null) {
      container.stop();
    }
    if (template != null) {
      template.destroy();
    }
  }

  private DefaultKafkaProducerFactory<String, String> producerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new DefaultKafkaProducerFactory<>(props);
  }

  private void createTopics() throws Exception {
    Map<String, Object> config = Map.of("bootstrap.servers", KAFKA.getBootstrapServers());
    try (Admin admin = Admin.create(config)) {
      var existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
      List<NewTopic> missing =
          List.of(topic, dlq).stream()
              .filter(name -> !existing.contains(name))
              .map(name -> new NewTopic(name, 1, (short) 1))
              .toList();
      if (!missing.isEmpty()) {
        admin.createTopics(missing).all().get(30, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void aLifecycleEventOnTheRealBrokerReachesTheConsumer() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID fileId = UUID.randomUUID();
    String payload =
        "{\"tenantId\":\""
            + tenantId
            + "\",\"fileId\":\""
            + fileId
            + "\",\"eventType\":\"DOCUMENT_VERSION_CREATED\",\"version\":2,"
            + "\"size\":42,\"format\":\"pdf\",\"objectKey\":\""
            + fileId
            + "/v2\"}";

    template.send(topic, fileId.toString(), payload).get(30, TimeUnit.SECONDS);

    assertThat(applied.await(30, TimeUnit.SECONDS)).isTrue();
    verify(service, timeout(30_000))
        .applyEvent(
            new DocumentLifecycleEvent(
                tenantId, fileId, "DOCUMENT_VERSION_CREATED", 2, 42L, "pdf", fileId + "/v2"));
    assertThat(dlqIsEmpty()).isTrue();
  }

  @Test
  void aMalformedRecordIsDeadLettered() throws Exception {
    String malformed = "{\"tenantId\":\"not-a-uuid\"}";

    template.send(topic, "bad", malformed).get(30, TimeUnit.SECONDS);

    assertThat(readDlq(30)).isEqualTo(malformed);
  }

  private boolean dlqIsEmpty() {
    return readDlq(2).isEmpty();
  }

  private String readDlq(long timeoutSeconds) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "document-dlq-it-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(dlq));
      long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
      while (System.nanoTime() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        if (!records.isEmpty()) {
          return records.iterator().next().value();
        }
      }
      return "";
    }
  }
}
