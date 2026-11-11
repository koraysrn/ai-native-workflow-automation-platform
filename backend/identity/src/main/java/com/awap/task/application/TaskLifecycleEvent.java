package com.awap.task.application;

import java.util.UUID;

/**
 * A task lifecycle fact (issue #203). Written to the transactional outbox by {@link TaskService}
 * and fanned out in-process so the notification module can turn it into a durable notification
 * request without the task module depending on notification.
 *
 * @param eventType TASK_ASSIGNED, TASK_APPROVED, TASK_REJECTED, TASK_COMPLETED or TASK_ESCALATED
 * @param actorId user that performed the transition, {@code null} for system/creation paths
 * @param ownerId task creator from JPA auditing, used as the recipient fallback after the assignee
 */
public record TaskLifecycleEvent(
    UUID tenantId,
    UUID taskId,
    String eventType,
    String title,
    UUID assigneeId,
    UUID ownerId,
    UUID actorId,
    UUID workflowInstanceId) {}
