package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Contract test for the document event-type-to-topic mapping (issue #208). */
@ExtendWith(MockitoExtension.class)
class DocumentEventRoutingTest {

  private static final Duration LEASE = Duration.ofSeconds(60);

  @Mock private OutboxClaimStore claimStore;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  @SuppressWarnings("unchecked")
  @Mock
  private ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;

  static Stream<String> documentEventTypes() {
    return Stream.of(
        "DOCUMENT_REGISTERED",
        "DOCUMENT_VERSION_CREATED",
        "DOCUMENT_ARCHIVED",
        "DOCUMENT_DELETED",
        "DOCUMENT_CHANGED");
  }

  @ParameterizedTest
  @MethodSource("documentEventTypes")
  void everyDocumentLifecycleEventGoesToTheDocumentTopic(String eventType) {
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
            UUID.randomUUID(), eventType, UUID.randomUUID(), "{\"fileId\":\"x\"}", token, null);
    when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
    when(claimStore.claimBatch(100, LEASE, 5)).thenReturn(List.of(event));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
    when(claimStore.markPublished(event.id(), token)).thenReturn(true);

    service.relayEvents();

    ArgumentCaptor<ProducerRecord<String, String>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(captor.capture());
    assertThat(captor.getValue().topic()).isEqualTo("document.events");
    assertThat(captor.getValue().key()).isEqualTo(event.aggregateId().toString());
  }
}
