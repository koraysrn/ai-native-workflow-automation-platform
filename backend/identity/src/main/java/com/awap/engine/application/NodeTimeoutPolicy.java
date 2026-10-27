package com.awap.engine.application;

import com.awap.workflow.domain.NodeType;
import java.util.Locale;
import java.util.Map;

/**
 * Maps every node type to the timeout guarantee the engine can honour for it.
 *
 * <p>The table is keyed by the node type <em>name</em> instead of the {@link NodeType} enum so a
 * node type that only exists in a newer workflow definition (or was removed) can never silently
 * inherit a hard guarantee. Unknown types are {@link NodeTimeoutEnforcement#UNSUPPORTED}.
 *
 * <p>{@code SCRIPT} is intentionally listed although {@link NodeType} does not contain it yet: the
 * sandboxed script executor is required by the constitution to be time-boxed, so the policy is
 * ready before the node type lands.
 */
public final class NodeTimeoutPolicy {

  /** Reserved node type name of the sandboxed script node (see the constitution). */
  public static final String SCRIPT_NODE_TYPE = "SCRIPT";

  private static final Map<String, NodeTimeoutEnforcement> ENFORCEMENT_BY_TYPE =
      Map.ofEntries(
          Map.entry("WEBHOOK", NodeTimeoutEnforcement.ENFORCED),
          Map.entry("AI", NodeTimeoutEnforcement.ENFORCED),
          Map.entry(SCRIPT_NODE_TYPE, NodeTimeoutEnforcement.ENFORCED),
          Map.entry("FORM", NodeTimeoutEnforcement.BEST_EFFORT),
          Map.entry("APPROVAL", NodeTimeoutEnforcement.BEST_EFFORT),
          Map.entry("TASK", NodeTimeoutEnforcement.BEST_EFFORT),
          Map.entry("DELAY", NodeTimeoutEnforcement.BEST_EFFORT),
          Map.entry("SUB_PROCESS", NodeTimeoutEnforcement.BEST_EFFORT),
          Map.entry("DECISION", NodeTimeoutEnforcement.BEST_EFFORT),
          Map.entry("CONDITION", NodeTimeoutEnforcement.BEST_EFFORT),
          Map.entry("START", NodeTimeoutEnforcement.UNSUPPORTED),
          Map.entry("END", NodeTimeoutEnforcement.UNSUPPORTED),
          Map.entry("PARALLEL", NodeTimeoutEnforcement.UNSUPPORTED),
          Map.entry("PARALLEL_GATEWAY", NodeTimeoutEnforcement.UNSUPPORTED),
          Map.entry("MERGE", NodeTimeoutEnforcement.UNSUPPORTED),
          Map.entry("MERGE_GATEWAY", NodeTimeoutEnforcement.UNSUPPORTED));

  private NodeTimeoutPolicy() {}

  public static NodeTimeoutEnforcement enforcementFor(NodeType nodeType) {
    return nodeType == null ? NodeTimeoutEnforcement.UNSUPPORTED : enforcementFor(nodeType.name());
  }

  public static NodeTimeoutEnforcement enforcementFor(String nodeType) {
    if (nodeType == null || nodeType.isBlank()) {
      return NodeTimeoutEnforcement.UNSUPPORTED;
    }
    return ENFORCEMENT_BY_TYPE.getOrDefault(
        nodeType.toUpperCase(Locale.ROOT), NodeTimeoutEnforcement.UNSUPPORTED);
  }

  /** Immutable view of the policy, used by documentation and tests. */
  public static Map<String, NodeTimeoutEnforcement> enforcementTable() {
    return ENFORCEMENT_BY_TYPE;
  }
}
