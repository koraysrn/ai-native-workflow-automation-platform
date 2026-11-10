package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.common.correlation.CorrelationId;
import com.awap.engine.infrastructure.CorrelationIdKafkaHeaders;
import com.awap.engine.infrastructure.CorrelationIdRecordInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Cross-hop contract test: an HTTP request's correlation ID survives into a Kafka record header and
 * is restored for the consumer listener with the same value (issue #191).
 */
class CorrelationIdEndToEndTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void theSameIdTravelsFromTheHttpRequestThroughTheKafkaHeaderToTheConsumer() throws Exception {
    var filter = new com.awap.common.correlation.CorrelationIdFilter();
    var request = new MockHttpServletRequest("POST", "/api/v1/tasks");
    request.addHeader(CorrelationId.HEADER, "http-hop-1");
    var response = new MockHttpServletResponse();
    AtomicReference<String> requestId = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) -> requestId.set(MDC.get(CorrelationId.MDC_KEY)));

    ProducerRecord<String, String> record =
        CorrelationIdKafkaHeaders.withCorrelationId(
            new ProducerRecord<>("workflow.task.events", "key", "{}"), requestId.get());
    ConsumerRecord<String, String> consumerRecord =
        new ConsumerRecord<>(record.topic(), 0, 0L, record.key(), record.value());
    consumerRecord
        .headers()
        .add(CorrelationId.HEADER, requestId.get().getBytes(StandardCharsets.UTF_8));
    new CorrelationIdRecordInterceptor().intercept(consumerRecord, null);

    assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("http-hop-1");
    assertThat(requestId.get()).isEqualTo("http-hop-1");
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("http-hop-1");
    assertThat(
            new String(
                consumerRecord.headers().lastHeader(CorrelationId.HEADER).value(),
                StandardCharsets.UTF_8))
        .isEqualTo("http-hop-1");
  }
}
