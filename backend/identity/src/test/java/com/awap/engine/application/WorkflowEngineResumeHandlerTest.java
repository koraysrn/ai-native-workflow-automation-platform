package com.awap.engine.application;

import static org.mockito.Mockito.verify;

import com.awap.engine.domain.NodeExecution;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the resume handler delegates due executions back into the engine. */
@ExtendWith(MockitoExtension.class)
class WorkflowEngineResumeHandlerTest {

  @Mock private WorkflowEngineService workflowEngineService;

  @Test
  void delegatesToEngine() {
    WorkflowEngineResumeHandler handler = new WorkflowEngineResumeHandler(workflowEngineService);
    NodeExecution execution =
        new NodeExecution(UUID.randomUUID(), UUID.randomUUID(), "delay-1", "DELAY");

    handler.handle(execution);

    verify(workflowEngineService)
        .resumeDueNode(
            execution.getTenantId(), execution.getWorkflowInstanceId(), execution.getNodeId());
  }
}
