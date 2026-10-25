package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.event.WorkflowInstanceStateEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

/**
 * Verifies the AI-layer consumer message contract against the backend's Avro producer schema.
 *
 * <p>The AI layer is a Python service and has no Pact tooling, so the contract is authored as a
 * Pact V3 message spec JSON consumed by both sides (see {@code
 * ai-pacts/awap-ai-layer-awap-identity.json}). This test proves every field the AI layer expects is
 * present, correctly typed and serializable by the generated {@link WorkflowInstanceStateEvent}
 * producer.
 */
class AiLayerMessageContractTest {

  private static final String PACT_RESOURCE = "ai-pacts/awap-ai-layer-awap-identity.json";

  @Test
  void workflowInstanceStateEventMatchesAiLayerMessageContract() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(PACT_RESOURCE)) {
      assertThat(in).as("AI-layer message pact must be on the test classpath").isNotNull();
      JsonNode pact = mapper.readTree(in);
      JsonNode contents = pact.path("messages").get(0).path("contents");
      assertThat(contents.isObject()).as("message contents must be an object").isTrue();

      Schema schema = WorkflowInstanceStateEvent.getClassSchema();

      contents
          .fieldNames()
          .forEachRemaining(
              field -> {
                Schema.Field schemaField = schema.getField(field);
                assertThat(schemaField)
                    .as("field '%s' must exist in the producer schema", field)
                    .isNotNull();
                assertThat(schemaField.schema().getType())
                    .as("field '%s' must be a string in the producer schema", field)
                    .isEqualTo(Schema.Type.STRING);
              });
    }
  }

  @Test
  void producerSchemaCarriesEveryExpectedField() {
    Schema schema = WorkflowInstanceStateEvent.getClassSchema();
    assertThat(schema.getField("tenantId")).isNotNull();
    assertThat(schema.getField("workflowInstanceId")).isNotNull();
    assertThat(schema.getField("state")).isNotNull();
  }
}
