package com.awap.task.application;

import java.util.UUID;

/**
 * Payload of the dedicated {@code task.escalation.events} topic (issue #207).
 *
 * <p>The escalation fact used to be published on {@code workflow.task.events}, where {@code
 * NodeCompletedEventListener} tried to deserialize it as a node-completion event and dead-lettered
 * it. The tenant id travels with the payload so the consumer can restore the tenant context (and
 * RLS) before touching the task.
 *
 * @param tenantId tenant that owns the task
 * @param taskId task whose escalation deadline elapsed
 * @param assigneeId configured escalation target (manager/backup), may be {@code null}
 */
public record TaskEscalationDue(UUID tenantId, UUID taskId, UUID assigneeId) {}
