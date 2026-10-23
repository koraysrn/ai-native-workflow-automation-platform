package com.awap.engine.application;

import com.awap.engine.domain.NodeExecution;
import org.springframework.stereotype.Component;

/**
 * Real {@link NodeResumeHandler}: resumes a delayed node execution by delegating to the workflow
 * engine, which completes the node and advances the workflow deterministically.
 */
@Component
public class WorkflowEngineResumeHandler implements NodeResumeHandler {

  private final WorkflowEngineService workflowEngineService;

  public WorkflowEngineResumeHandler(WorkflowEngineService workflowEngineService) {
    this.workflowEngineService = workflowEngineService;
  }

  @Override
  public void handle(NodeExecution execution) {
    workflowEngineService.resumeDueNode(
        execution.getTenantId(), execution.getWorkflowInstanceId(), execution.getNodeId());
  }
}
