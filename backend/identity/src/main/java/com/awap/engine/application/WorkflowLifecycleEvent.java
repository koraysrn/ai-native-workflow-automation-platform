package com.awap.engine.application;

import java.util.UUID;

/**
 * A workflow lifecycle fact (issue #203). Written to the transactional outbox by {@link
 * WorkflowEngineService} and fanned out in-process for the notification producer.
 *
 * @param eventType WORKFLOW_STARTED, WORKFLOW_COMPLETED, WORKFLOW_FAILED, WORKFLOW_PAUSED or
 *     WORKFLOW_RESUMED
 * @param state state the instance reached, as persisted
 */
public record WorkflowLifecycleEvent(
    UUID tenantId, UUID instanceId, UUID workflowDefinitionId, String eventType, String state) {}
