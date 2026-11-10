package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.common.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdRecordInterceptorTest {

  private final CorrelationIdRecordInterceptor interceptor = new CorrelationIdRecordInterceptor();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private ConsumerRecord<String, String> recordWithHeader(String value) {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("workflow.task.events", 0, 0L, "k", "v");
    record.headers().add(CorrelationId.HEADER, value.getBytes(StandardCharsets.UTF_8));
    return record;
  }

  @Test
  void restoresTheHeaderForTheListenerAndClearsItAfterwards() {
    ConsumerRecord<String, String> record = recordWithHeader("corr-1");

    interceptor.intercept(record, null);
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("corr-1");

    interceptor.afterRecord(record, null);
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  void generatesAnIdWhenTheHeaderIsMissing() {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("workflow.task.events", 0, 0L, "k", "v");

    interceptor.intercept(record, null);
    assertThat(CorrelationId.current()).isPresent();
    assertThat(CorrelationId.isValid(CorrelationId.current().orElseThrow())).isTrue();

    interceptor.afterRecord(record, null);
    assertThat(CorrelationId.current()).isEmpty();
  }

  @Test
  void replacesAnUnsafeHeaderAndClearsItOnFailure() {
    ConsumerRecord<String, String> record = recordWithHeader("bad\nvalue");

    interceptor.intercept(record, null);
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNotEqualTo("bad\nvalue");

    interceptor.failure(record, new IllegalStateException("boom"), null);
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }
}
