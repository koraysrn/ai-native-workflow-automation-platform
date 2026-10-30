package com.awap.engine.application;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.workflow.domain.NodeDefinition;

/**
 * Callbacks the extracted engine collaborators need from {@link WorkflowEngineService}. The engine
 * implements this interface so {@link NodeExecutor} and {@link TransitionResolver} never depend
 * back on the concrete facade, avoiding a Spring bean cycle.
 */
interface WorkflowEnginePort {

  void transition(WorkflowInstance instance, WorkflowState target, String action);

  void log(
      WorkflowInstance instance, String nodeId, String nodeType, String action, String payload);

  void executeNode(WorkflowInstance instance, NodeDefinition node);

  void advanceFrom(WorkflowInstance instance, String fromNodeId);

  void startSubProcess(WorkflowInstance parent, NodeDefinition node, NodeExecution execution);

  void recordSagaStep(WorkflowInstance instance, NodeDefinition node, NodeExecution execution);
}
