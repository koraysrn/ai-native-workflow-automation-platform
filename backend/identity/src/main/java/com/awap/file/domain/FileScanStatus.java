package com.awap.file.domain;

import java.util.Map;
import java.util.Set;

/**
 * Antivirus scan lifecycle of an uploaded file.
 *
 * <p>The lifecycle is a guarded state machine: files start in {@link #QUARANTINED}, move to {@link
 * #SCANNING} while the scheduler streams them to ClamAV and end in {@link #PROMOTED}, {@link
 * #INFECTED} or {@link #SCAN_FAILED}. A failed scan can be retried (back to {@code SCANNING}); a
 * clean file is promoted once the server-side copy to the promoted bucket succeeded.
 */
public enum FileScanStatus {
  QUARANTINED,
  SCANNING,
  CLEAN,
  PROMOTED,
  INFECTED,
  SCAN_FAILED;

  private static final Map<FileScanStatus, Set<FileScanStatus>> ALLOWED_TRANSITIONS =
      Map.of(
          QUARANTINED, Set.of(SCANNING),
          SCANNING, Set.of(CLEAN, INFECTED, SCAN_FAILED),
          CLEAN, Set.of(PROMOTED),
          SCAN_FAILED, Set.of(SCANNING),
          PROMOTED, Set.of(),
          INFECTED, Set.of());

  public boolean canTransitionTo(FileScanStatus target) {
    return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
  }

  /**
   * Validates and returns the target state.
   *
   * @throws IllegalStateException when the transition is not part of the lifecycle
   */
  public FileScanStatus transitionTo(FileScanStatus target) {
    if (!canTransitionTo(target)) {
      throw new IllegalStateException("Illegal file scan transition " + this + " -> " + target);
    }
    return target;
  }

  public boolean isTerminal() {
    return this == PROMOTED || this == INFECTED;
  }

  public boolean isScannable() {
    return this == QUARANTINED || this == SCAN_FAILED;
  }

  public boolean isPromotable() {
    return this == CLEAN;
  }
}
