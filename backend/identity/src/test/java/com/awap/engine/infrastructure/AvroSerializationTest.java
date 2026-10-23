package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.event.AnalyticsEvent;
import com.awap.engine.event.DocumentEvent;
import com.awap.engine.event.NotificationRequestedEvent;
import com.awap.engine.event.TaskCompletedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Round-trip tests proving the generated Avro schemas serialize and deserialize correctly. */
class AvroSerializationTest {

  private final AvroEventSerde serde = new AvroEventSerde();

  @Test
  void roundTripsTaskCompletedEvent() {
    TaskCompletedEvent original =
        TaskCompletedEvent.newBuilder()
            .setTenantId(UUID.randomUUID().toString())
            .setWorkflowInstanceId(UUID.randomUUID().toString())
            .setNodeExecutionId(UUID.randomUUID().toString())
            .setOutputJson("{\"decision\":\"APPROVED\"}")
            .build();

    TaskCompletedEvent roundTripped =
        serde.deserialize(serde.serialize(original), TaskCompletedEvent.class);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void roundTripsAnalyticsEvent() {
    AnalyticsEvent original =
        AnalyticsEvent.newBuilder()
            .setTenantId(UUID.randomUUID().toString())
            .setEventType("TASK_COMPLETED")
            .setWorkflowInstanceId(UUID.randomUUID().toString())
            .setDurationMs(1234L)
            .setTimestamp(System.currentTimeMillis())
            .build();

    AnalyticsEvent roundTripped =
        serde.deserialize(serde.serialize(original), AnalyticsEvent.class);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void roundTripsNotificationRequestedEvent() {
    NotificationRequestedEvent original =
        NotificationRequestedEvent.newBuilder()
            .setTenantId(UUID.randomUUID().toString())
            .setRecipient("user@example.com")
            .setChannel("EMAIL")
            .setTemplateKey("task.assigned")
            .setVariablesJson("{\"title\":\"Review\"}")
            .build();

    NotificationRequestedEvent roundTripped =
        serde.deserialize(serde.serialize(original), NotificationRequestedEvent.class);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void roundTripsDocumentEvent() {
    DocumentEvent original =
        DocumentEvent.newBuilder()
            .setTenantId(UUID.randomUUID().toString())
            .setFileId(UUID.randomUUID().toString())
            .setEventType("FILE_UPLOADED")
            .setSize(1024L)
            .setFormat("pdf")
            .build();

    DocumentEvent roundTripped = serde.deserialize(serde.serialize(original), DocumentEvent.class);

    assertThat(roundTripped).isEqualTo(original);
  }
}
