package com.awap.engine.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

/** Verifies the DLQ consumer records a metric for each dead-letter message. */
class DlqConsumerTest {

  @Test
  void incrementsDlqCounterForEachMessage() {
    MeterRegistry registry = mock(MeterRegistry.class);
    Counter counter = mock(Counter.class);
    when(registry.counter("awap.dlq.messages")).thenReturn(counter);

    DlqConsumer consumer = new DlqConsumer(registry);
    consumer.handle("{\"broken\":true}");

    verify(counter).increment();
  }
}
