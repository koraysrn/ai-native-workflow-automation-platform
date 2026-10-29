package com.awap.engine.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** Verifies the listener delegates every record to the idempotent handler. */
class DlqConsumerTest {

  @Test
  void delegatesRecordToHandler() {
    DlqMessageHandler handler = mock(DlqMessageHandler.class);
    DlqConsumer consumer = new DlqConsumer(handler);
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("awap.dlq", 0, 11L, "key", "{\"broken\":true}");

    consumer.onMessage(record);

    verify(handler).handle(record);
    verifyNoMoreInteractions(handler);
  }
}
