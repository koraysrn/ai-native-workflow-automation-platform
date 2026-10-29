package com.awap.engine.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Explicit dictionary of the {@code execution_logs.action} tokens the replay reducer understands.
 *
 * <p>The dictionary is exhaustive on purpose: an unknown or future action must fail closed instead
 * of being silently skipped, otherwise a newer writer could make replay produce a wrong state
 * without any signal.
 */
public enum ReplayAction {
  INSTANCE_STARTED,
  NODE_WAITING,
  NODE_DELAYED,
  NODE_FORKED,
  NODE_MERGED,
  NODE_COMPLETED,
  NODE_FAILED,
  NODE_RETRY_SCHEDULED,
  NODE_RETRY_STARTED,
  NODE_REPLAYED,
  NODE_REPLAY_SKIPPED,
  WORKFLOW_COMPLETED,
  WORKFLOW_FAILED,
  WORKFLOW_CANCELLED,
  WORKFLOW_PAUSED,
  WORKFLOW_RESUMED,
  WORKFLOW_RETRIED,
  WORKFLOW_REPLAYED,
  WORKFLOW_RECOVERED,
  SUB_PROCESS_FAILED,
  REPLAY_EXTERNAL_CALL_SUPPRESSED;

  private static final Map<String, ReplayAction> DICTIONARY =
      Collections.unmodifiableMap(
          Arrays.stream(values())
              .collect(Collectors.toMap(Enum::name, Function.identity(), (a, b) -> a)));

  /** Returns the reducer action for a raw log token, or empty when the token is unknown. */
  public static Optional<ReplayAction> fromToken(String token) {
    if (token == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(DICTIONARY.get(token));
  }

  /** The full set of tokens the reducer supports; used by documentation and tests. */
  public static Set<String> tokens() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(DICTIONARY.keySet()));
  }
}
