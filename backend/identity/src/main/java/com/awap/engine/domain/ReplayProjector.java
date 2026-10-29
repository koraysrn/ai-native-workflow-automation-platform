package com.awap.engine.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic reducer: folds ordered {@link ReplayLogEntry} rows into an immutable {@link
 * ReplayState}.
 *
 * <p>The projector is streaming by design — it holds only the current state, the divergences found
 * so far and the ordering cursor, so an arbitrarily large log can be replayed page by page without
 * materializing it. Unknown actions, out-of-order rows and malformed node actions fail closed and
 * stop the reduction; they are never skipped silently.
 */
public final class ReplayProjector {

  private final ReplayOutputSummarizer summarizer;
  private final List<ReplayDivergence> divergences = new ArrayList<>();

  private ReplayState state;
  private ReplayLogEntry lastEntry;
  private boolean failedClosed;
  private int appliedRows;

  public ReplayProjector(UUID instanceId, ReplayOutputSummarizer summarizer) {
    this.state = ReplayState.initial(Objects.requireNonNull(instanceId, "instanceId"));
    this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
  }

  /**
   * Applies a single log row in deterministic order.
   *
   * @return {@code true} while the projection can continue, {@code false} once it failed closed
   */
  public boolean apply(ReplayLogEntry entry) {
    Objects.requireNonNull(entry, "entry");
    if (failedClosed) {
      return false;
    }
    if (lastEntry != null && ReplayLogOrder.INSTANCE.compare(lastEntry, entry) > 0) {
      divergences.add(
          ReplayDivergence.of(
              ReplayDivergenceType.OUT_OF_ORDER_LOG,
              entry.nodeId().orElse(null),
              "log " + entry.id() + " arrived after " + lastEntry.id()));
      failedClosed = true;
      return false;
    }
    lastEntry = entry;

    Optional<ReplayAction> action = ReplayAction.fromToken(entry.action());
    if (action.isEmpty()) {
      divergences.add(
          ReplayDivergence.of(
              ReplayDivergenceType.UNKNOWN_ACTION,
              entry.nodeId().orElse(null),
              "unsupported log action '" + entry.action() + "'"));
      failedClosed = true;
      return false;
    }

    state = reduce(action.get(), entry);
    if (!failedClosed) {
      state = state.withAppliedActions(state.appliedActions() + 1);
      appliedRows++;
    }
    return !failedClosed;
  }

  /** Immutable snapshot of the projection built so far. */
  public ReplayProjection result() {
    return new ReplayProjection(state, divergences, failedClosed, appliedRows);
  }

  private ReplayState reduce(ReplayAction action, ReplayLogEntry entry) {
    return switch (action) {
      case INSTANCE_STARTED -> state.withInstanceState(WorkflowState.STARTED, Optional.empty());
      case WORKFLOW_COMPLETED ->
          state.withInstanceState(WorkflowState.COMPLETED, state.currentNodeId());
      case WORKFLOW_FAILED, SUB_PROCESS_FAILED, REPLAY_EXTERNAL_CALL_SUPPRESSED ->
          state.withInstanceState(WorkflowState.FAILED, state.currentNodeId());
      case WORKFLOW_CANCELLED ->
          state.withInstanceState(WorkflowState.CANCELLED, state.currentNodeId());
      case WORKFLOW_PAUSED -> state.withInstanceState(WorkflowState.PAUSED, state.currentNodeId());
      case WORKFLOW_RESUMED, WORKFLOW_RETRIED, WORKFLOW_REPLAYED, WORKFLOW_RECOVERED ->
          state.withInstanceState(WorkflowState.RUNNING, state.currentNodeId());
      case NODE_WAITING, NODE_DELAYED ->
          withNode(
              entry,
              nodeId ->
                  state
                      .withNodeStatus(nodeId, NodeExecutionStatus.WAITING)
                      .withInstanceState(WorkflowState.WAITING, Optional.of(nodeId)));
      case NODE_FORKED ->
          withNode(
              entry,
              nodeId ->
                  state
                      .withNodeStatus(nodeId, NodeExecutionStatus.COMPLETED)
                      .withBranchCounters(nodeId, readInt(entry.payload(), "branches", 1), 0));
      case NODE_MERGED ->
          withNode(
              entry,
              nodeId ->
                  state.withMergedBranches(
                      nodeId, readInt(entry.payload(), "merged", mergedOf(nodeId) + 1)));
      case NODE_COMPLETED ->
          withNode(
              entry,
              nodeId ->
                  completeNode(entry, nodeId)
                      .withInstanceState(WorkflowState.RUNNING, Optional.of(nodeId)));
      case NODE_REPLAYED ->
          withNode(
              entry,
              nodeId ->
                  state
                      .withNodeStatus(nodeId, NodeExecutionStatus.COMPLETED)
                      .withInstanceState(WorkflowState.RUNNING, Optional.of(nodeId)));
      case NODE_FAILED ->
          entry
              .nodeId()
              .map(nodeId -> state.withNodeStatus(nodeId, NodeExecutionStatus.FAILED))
              .orElseGet(
                  () -> state.withInstanceState(WorkflowState.FAILED, state.currentNodeId()));
      case NODE_RETRY_SCHEDULED ->
          withNode(entry, nodeId -> state.withNodeStatus(nodeId, NodeExecutionStatus.FAILED));
      case NODE_RETRY_STARTED ->
          entry
              .nodeId()
              .map(
                  nodeId ->
                      state
                          .withNodeStatus(nodeId, NodeExecutionStatus.RUNNING)
                          .withInstanceState(WorkflowState.RUNNING, Optional.of(nodeId)))
              .orElseGet(
                  () -> state.withInstanceState(WorkflowState.RUNNING, state.currentNodeId()));
      case NODE_REPLAY_SKIPPED ->
          withNode(
              entry,
              nodeId ->
                  state
                      .withNodeStatus(nodeId, NodeExecutionStatus.FAILED)
                      .withInstanceState(WorkflowState.FAILED, Optional.of(nodeId)));
    };
  }

  private ReplayState completeNode(ReplayLogEntry entry, String nodeId) {
    ReplayState completed = state.withNodeStatus(nodeId, NodeExecutionStatus.COMPLETED);
    boolean external = entry.nodeType().map(ReplayProjector::isExternalCall).orElse(false);
    Optional<String> payload = entry.payload().filter(value -> !value.isBlank());
    if (payload.isEmpty()) {
      if (external) {
        // Replay never performs the call itself; without a recorded result there is nothing to
        // fold.
        divergences.add(
            ReplayDivergence.of(
                ReplayDivergenceType.EXTERNAL_RESULT_MISSING,
                nodeId,
                "external call has no recorded result; replay must not invoke it"));
      }
      return completed;
    }
    ReplayPayloadSummary summary = summarizer.summarize(payload.get());
    if (!summary.validJson()) {
      divergences.add(
          ReplayDivergence.of(
              ReplayDivergenceType.MALFORMED_PAYLOAD, nodeId, "payload is not valid JSON"));
      return completed;
    }
    if (summary.summary().isBlank()) {
      return completed;
    }
    return completed.withOutputSummary(nodeId, summary.summary());
  }

  private ReplayState withNode(
      ReplayLogEntry entry, java.util.function.Function<String, ReplayState> update) {
    if (entry.nodeId().isEmpty()) {
      divergences.add(
          ReplayDivergence.of(
              ReplayDivergenceType.MALFORMED_LOG,
              null,
              "action " + entry.action() + " requires a node id"));
      failedClosed = true;
      return state;
    }
    return update.apply(entry.nodeId().get());
  }

  private int mergedOf(String nodeId) {
    return state.mergedBranches().getOrDefault(nodeId, 0);
  }

  private static boolean isExternalCall(String nodeType) {
    String normalized = nodeType.toUpperCase(java.util.Locale.ROOT);
    return normalized.equals("WEBHOOK")
        || normalized.startsWith("WEBHOOK_")
        || normalized.equals("NOTIFICATION")
        || normalized.startsWith("NOTIFICATION_");
  }

  private static int readInt(Optional<String> payload, String key, int fallback) {
    if (payload.isEmpty()) {
      return fallback;
    }
    Matcher matcher =
        Pattern.compile("(?:^|[;,\\s])" + Pattern.quote(key) + "=(-?\\d+)").matcher(payload.get());
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
  }
}
