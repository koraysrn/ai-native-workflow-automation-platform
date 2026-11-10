package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.awap.common.correlation.CorrelationId;
import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;

class OutboxEventPublisherCorrelationTest {

  private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
  private final ApplicationEventPublisher listeners = mock(ApplicationEventPublisher.class);
  private final OutboxEventPublisher publisher =
      new OutboxEventPublisher(repository, new ObjectMapper(), listeners);

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void capturesTheCurrentCorrelationIdForTheOutboxRow() {
    MDC.put(CorrelationId.MDC_KEY, "corr-outbox");

    publisher.publish(
        UUID.randomUUID(), "TASK", UUID.randomUUID(), "TASK_COMPLETED", Map.of("x", 1));

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getCorrelationId()).isEqualTo("corr-outbox");
  }

  @Test
  void generatesACorrelationIdWhenThereIsNoRequestContext() {
    publisher.publish(
        UUID.randomUUID(), "TASK", UUID.randomUUID(), "TASK_COMPLETED", Map.of("x", 1));

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getCorrelationId()).isNotBlank();
    assertThat(CorrelationId.isValid(captor.getValue().getCorrelationId())).isTrue();
  }

  @Test
  void publishesTheLocalEventOnTheSameCorrelationId() {
    MDC.put(CorrelationId.MDC_KEY, "corr-local");
    Object payload = Map.of("x", 1);

    publisher.publish(UUID.randomUUID(), "TASK", UUID.randomUUID(), "TASK_COMPLETED", payload);

    verify(listeners).publishEvent(payload);
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("corr-local");
  }
}
