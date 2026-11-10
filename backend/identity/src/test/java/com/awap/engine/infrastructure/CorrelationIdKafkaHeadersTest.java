package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.common.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;

class CorrelationIdKafkaHeadersTest {

  private ProducerRecord<String, String> record() {
    return new ProducerRecord<>("workflow.task.events", "key", "{}");
  }

  @Test
  void addsTheHeaderWhenACorrelationIdExists() {
    Header header =
        CorrelationIdKafkaHeaders.withCorrelationId(record(), "corr-1")
            .headers()
            .lastHeader(CorrelationId.HEADER);

    assertThat(header).isNotNull();
    assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("corr-1");
  }

  @Test
  void leavesTheRecordUnchangedForNullAndBlankValues() {
    assertThat(
            CorrelationIdKafkaHeaders.withCorrelationId(record(), null)
                .headers()
                .lastHeader(CorrelationId.HEADER))
        .isNull();
    assertThat(
            CorrelationIdKafkaHeaders.withCorrelationId(record(), " ")
                .headers()
                .lastHeader(CorrelationId.HEADER))
        .isNull();
  }
}
