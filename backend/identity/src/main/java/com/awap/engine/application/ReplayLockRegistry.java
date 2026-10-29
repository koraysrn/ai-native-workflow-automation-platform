package com.awap.engine.application;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Single-flight guard for real (non-dry-run) replays, which mutate the instance. The second caller
 * for the same instance is rejected with {@code REPLAY_CONFLICT} (409) instead of racing the first
 * one into conflicting state.
 */
@Component
public class ReplayLockRegistry {

  private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

  /**
   * @return {@code true} when this caller now owns the instance lock
   */
  public boolean tryAcquire(UUID instanceId) {
    return inFlight.add(instanceId);
  }

  public void release(UUID instanceId) {
    inFlight.remove(instanceId);
  }

  public boolean isHeld(UUID instanceId) {
    return inFlight.contains(instanceId);
  }
}
