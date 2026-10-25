package com.awap.engine.infrastructure;

import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.awap.engine.event.WorkflowInstanceStateEvent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the AI-layer message pact with Pact's real message verifier. The consumer contract is a
 * Pact V3 message spec JSON ({@code ai-pacts/awap-ai-layer-awap-identity.json}); this provider test
 * serializes the actual {@link WorkflowInstanceStateEvent} producer and lets Pact compare it against
 * the shape the Python AI layer expects.
 */
@Provider("awap-identity")
@PactFolder("src/test/resources/ai-pacts")
class AiLayerMessageContractTest {

  @BeforeEach
  void setUp(PactVerificationContext context) {
    context.setTarget(new MessageTestTarget());
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void verifyMessageContract(PactVerificationContext context) {
    context.verifyInteraction();
  }

  @State("a workflow instance state change is emitted")
  void workflowInstanceStateChangeEmitted() {
    // No-op: the message is produced deterministically by the provider method below.
  }

  @PactVerifyProvider("a workflow instance state change event")
  String workflowInstanceStateChangeEvent() throws Exception {
    WorkflowInstanceStateEvent event =
        WorkflowInstanceStateEvent.newBuilder()
            .setTenantId("00000000-0000-0000-0000-000000000001")
            .setWorkflowInstanceId("00000000-0000-0000-0000-000000000030")
            .setState("RUNNING")
            .build();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Encoder encoder =
        EncoderFactory.get().jsonEncoder(WorkflowInstanceStateEvent.getClassSchema(), out);
    SpecificDatumWriter<WorkflowInstanceStateEvent> writer =
        new SpecificDatumWriter<>(WorkflowInstanceStateEvent.class);
    writer.write(event, encoder);
    encoder.flush();
    return out.toString(StandardCharsets.UTF_8);
  }
}
