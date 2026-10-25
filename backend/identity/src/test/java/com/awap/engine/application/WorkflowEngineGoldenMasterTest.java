package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.interfaces.dto.EngineMapperImpl;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Golden-master for the engine's public response shape. Any change to {@code
 * WorkflowInstanceResponse} or the {@code EngineMapper} mapping that breaks these pinned values is
 * a behavioral regression.
 */
class WorkflowEngineGoldenMasterTest {

  private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
  private static final UUID DEFINITION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
  private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void publicResponseShapeIsPinned() {
    WorkflowInstance instance =
        new WorkflowInstance(
            TENANT_ID, DEFINITION_ID, 1, WorkflowState.RUNNING, "key-1", "{\"x\":1}");
    ReflectionTestUtils.setField(instance, "id", INSTANCE_ID);
    ReflectionTestUtils.setField(instance, "createdAt", CREATED_AT);

    WorkflowInstanceResponse response = new EngineMapperImpl().toResponse(instance);

    assertThat(response.id()).isEqualTo(INSTANCE_ID);
    assertThat(response.tenantId()).isEqualTo(TENANT_ID);
    assertThat(response.workflowDefinitionId()).isEqualTo(DEFINITION_ID);
    assertThat(response.workflowVersion()).isEqualTo(1);
    assertThat(response.state()).isEqualTo("RUNNING");
    assertThat(response.contextJson()).isEqualTo("{\"x\":1}");
    assertThat(response.idempotencyKey()).isEqualTo("key-1");
    assertThat(response.createdAt()).isEqualTo(CREATED_AT);
  }
}
