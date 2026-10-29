package com.awap.usage.domain;

import java.util.UUID;

/**
 * Deterministic idempotency keys shared by the synchronous metering hooks and the Kafka consumer.
 *
 * <p>Both write paths derive the key from the same aggregate identity, so a redelivered event can
 * never add a second increment even when it races with (or follows) the transactional hook.
 */
public final class UsageEventKeys {

  private UsageEventKeys() {}

  public static String workflowStarted(UUID instanceId) {
    return "usage:workflow-started:" + instanceId;
  }

  public static String taskDecision(UUID taskId) {
    return "usage:task-decision:" + taskId;
  }

  public static String documentVersion(UUID versionId) {
    return "usage:document-version:" + versionId;
  }

  public static String aiCall(UUID callId) {
    return "usage:ai-call:" + callId;
  }
}
