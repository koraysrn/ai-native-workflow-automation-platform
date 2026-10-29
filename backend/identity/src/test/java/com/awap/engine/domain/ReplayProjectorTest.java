package com.awap.engine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplayProjectorTest {

  /**
   * Test-local redactor. The domain package must not depend on the application layer (ArchUnit), so
   * the production {@code JsonReplayPayloadSummarizer} is exercised by its own test instead.
   */
  private final ReplayOutputSummarizer summarizer = ReplayProjectorTest::redact;

  private static ReplayPayloadSummary redact(String payload) {
    if (payload == null || payload.isBlank()) {
      return ReplayPayloadSummary.none();
    }
    try {
      JsonNode node = new ObjectMapper().readTree(payload);
      if (node == null || node.isMissingNode()) {
        return ReplayPayloadSummary.invalid(payload.length());
      }
      List<String> keys = new ArrayList<>();
      if (node.isObject()) {
        node.fieldNames().forEachRemaining(keys::add);
      }
      keys.sort(String::compareTo);
      return new ReplayPayloadSummary(true, "keys=" + String.join(",", keys));
    } catch (JsonProcessingException ex) {
      return ReplayPayloadSummary.invalid(payload.length());
    }
  }

  private ReplayProjector newProjector() {
    return new ReplayProjector(ReplayTestFixtures.INSTANCE, summarizer);
  }

  private ReplayProjection project(List<ReplayLogEntry> entries) {
    ReplayProjector projector = newProjector();
    entries.stream().sorted(ReplayLogOrder.INSTANCE).forEach(projector::apply);
    return projector.result();
  }

  @Test
  void fullSequenceReproducesThePersistedTerminalState() {
    ReplayProjection projection = project(ReplayTestFixtures.happyPath());

    assertThat(projection.failedClosed()).isFalse();
    assertThat(projection.appliedRows()).isEqualTo(8);
    assertThat(projection.state().instanceState()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(projection.state().currentNodeId()).contains("end");
    assertThat(projection.state().nodeStatuses())
        .containsEntry("start", NodeExecutionStatus.COMPLETED)
        .containsEntry("form", NodeExecutionStatus.COMPLETED)
        .containsEntry("approval", NodeExecutionStatus.COMPLETED)
        .containsEntry("end", NodeExecutionStatus.COMPLETED);
    assertThat(projection.divergences()).isEmpty();
    assertThat(ReplayComparison.compare(projection.state(), ReplayTestFixtures.happyPathSnapshot()))
        .isEmpty();
  }

  @Test
  void outputSummaryIsRedactedAndNeverContainsValues() {
    ReplayProjection projection = project(ReplayTestFixtures.happyPath());

    String formSummary = projection.state().outputSummaries().get("form");
    assertThat(formSummary).contains("keys=answer,ssn").doesNotContain("123-45-6789");
    assertThat(projection.stateHash()).isNotBlank();
  }

  @Test
  void parallelSequenceReproducesBranchCounters() {
    ReplayProjection projection = project(ReplayTestFixtures.parallelPath());

    assertThat(projection.state().branchTokens()).containsEntry("fork", 2);
    assertThat(projection.state().mergedBranches()).containsEntry("fork", 2);
    assertThat(projection.state().nodeStatuses())
        .containsEntry("join", NodeExecutionStatus.COMPLETED);
    assertThat(projection.state().instanceState()).isEqualTo(WorkflowState.COMPLETED);

    PersistedReplaySnapshot persisted =
        new PersistedReplaySnapshot(
            WorkflowState.COMPLETED,
            Map.of(
                "fork", NodeExecutionStatus.COMPLETED,
                "branch-a", NodeExecutionStatus.COMPLETED,
                "branch-b", NodeExecutionStatus.COMPLETED,
                "join", NodeExecutionStatus.COMPLETED),
            Map.of("fork", new PersistedReplaySnapshot.BranchCounters(2, 2)));
    assertThat(ReplayComparison.compare(projection.state(), persisted)).isEmpty();
  }

  @Test
  void branchCounterMismatchIsReported() {
    ReplayProjection projection = project(ReplayTestFixtures.parallelPath());
    PersistedReplaySnapshot persisted =
        new PersistedReplaySnapshot(
            WorkflowState.COMPLETED,
            Map.of(
                "fork", NodeExecutionStatus.COMPLETED,
                "branch-a", NodeExecutionStatus.COMPLETED,
                "branch-b", NodeExecutionStatus.COMPLETED,
                "join", NodeExecutionStatus.COMPLETED),
            Map.of("fork", new PersistedReplaySnapshot.BranchCounters(3, 1)));

    List<ReplayDivergence> divergences = ReplayComparison.compare(projection.state(), persisted);

    assertThat(divergences)
        .extracting(ReplayDivergence::type)
        .contains(
            ReplayDivergenceType.BRANCH_TOKEN_MISMATCH, ReplayDivergenceType.BRANCH_MERGE_MISMATCH);
  }

  @Test
  void failedWorkflowReproducesFailedState() {
    List<ReplayLogEntry> entries =
        List.of(
            ReplayTestFixtures.entry(1, "INSTANCE_STARTED", null, null, "started"),
            ReplayTestFixtures.entry(2, "NODE_COMPLETED", "start", "START", null),
            ReplayTestFixtures.entry(3, "NODE_WAITING", "approval", "APPROVAL", null),
            ReplayTestFixtures.entry(4, "NODE_FAILED", "approval", "APPROVAL", "boom"),
            ReplayTestFixtures.entry(5, "NODE_FAILED", null, null, "State advanced to FAILED"));

    ReplayProjection projection = project(entries);

    assertThat(projection.state().instanceState()).isEqualTo(WorkflowState.FAILED);
    assertThat(projection.state().nodeStatus("approval")).contains(NodeExecutionStatus.FAILED);
    assertThat(ReplayComparison.compare(projection.state(), ReplayTestFixtures.happyPathSnapshot()))
        .extracting(ReplayDivergence::type)
        .contains(ReplayDivergenceType.INSTANCE_STATE_MISMATCH);
  }

  @Test
  void missingNodeCompletionIsDivergence() {
    List<ReplayLogEntry> withoutApprovalCompletion =
        ReplayTestFixtures.happyPath().stream()
            .filter(entry -> !"approval".equals(entry.nodeId().orElse("")))
            .toList();

    ReplayProjection projection = project(withoutApprovalCompletion);
    List<ReplayDivergence> divergences =
        ReplayComparison.compare(projection.state(), ReplayTestFixtures.happyPathSnapshot());

    assertThat(divergences)
        .extracting(ReplayDivergence::type)
        .contains(ReplayDivergenceType.MISSING_NODE_COMPLETION);
    assertThat(divergences)
        .filteredOn(d -> d.type() == ReplayDivergenceType.MISSING_NODE_COMPLETION)
        .allSatisfy(d -> assertThat(d.nodeId()).contains("approval"));
  }

  @Test
  void unknownActionFailsClosedAndIsNeverSkipped() {
    ReplayProjector projector = newProjector();
    projector.apply(ReplayTestFixtures.entry(1, "INSTANCE_STARTED", null, null, "started"));

    boolean continueAfterUnknown =
        projector.apply(ReplayTestFixtures.entry(2, "NODE_TELEPORTED", "start", "START", null));
    ReplayProjection projection = projector.result();

    assertThat(continueAfterUnknown).isFalse();
    assertThat(projection.failedClosed()).isTrue();
    assertThat(projection.divergences())
        .extracting(ReplayDivergence::type)
        .containsExactly(ReplayDivergenceType.UNKNOWN_ACTION);
    assertThat(projection.appliedRows()).isEqualTo(1);
    assertThat(projector.apply(ReplayTestFixtures.entry(3, "WORKFLOW_COMPLETED", null, null, null)))
        .isFalse();
  }

  @Test
  void sameTimestampEntriesAreOrderedById() {
    Instant sameInstant = ReplayTestFixtures.BASE.plusSeconds(10);
    ReplayLogEntry second =
        ReplayTestFixtures.entry(
            new UUID(0L, 2L), sameInstant, "NODE_COMPLETED", "b", "TASK", null);
    ReplayLogEntry first =
        ReplayTestFixtures.entry(new UUID(0L, 1L), sameInstant, "NODE_WAITING", "b", "TASK", null);

    ReplayProjection sorted = project(List.of(second, first));

    assertThat(sorted.failedClosed()).isFalse();
    assertThat(sorted.appliedRows()).isEqualTo(2);
    assertThat(sorted.state().nodeStatus("b")).contains(NodeExecutionStatus.COMPLETED);
    assertThat(sorted.state().appliedActions()).isEqualTo(2);

    ReplayProjector outOfOrder = newProjector();
    assertThat(outOfOrder.apply(second)).isTrue();
    assertThat(outOfOrder.apply(first)).isFalse();
    assertThat(outOfOrder.result().divergences())
        .extracting(ReplayDivergence::type)
        .containsExactly(ReplayDivergenceType.OUT_OF_ORDER_LOG);
  }

  @Test
  void malformedPayloadIsDivergenceNotCrash() {
    List<ReplayLogEntry> entries =
        List.of(
            ReplayTestFixtures.entry(1, "INSTANCE_STARTED", null, null, "started"),
            ReplayTestFixtures.entry(2, "NODE_COMPLETED", "form", "FORM", "{not-json"));

    ReplayProjection projection = project(entries);

    assertThat(projection.failedClosed()).isFalse();
    assertThat(projection.state().nodeStatus("form")).contains(NodeExecutionStatus.COMPLETED);
    assertThat(projection.state().outputSummaries()).doesNotContainKey("form");
    assertThat(projection.divergences())
        .extracting(ReplayDivergence::type)
        .containsExactly(ReplayDivergenceType.MALFORMED_PAYLOAD);
  }

  @Test
  void externalCallWithoutRecordedResultIsDivergenceAndNeverInvoked() {
    List<ReplayLogEntry> entries =
        List.of(
            ReplayTestFixtures.entry(1, "INSTANCE_STARTED", null, null, "started"),
            ReplayTestFixtures.entry(2, "NODE_COMPLETED", "hook", "WEBHOOK", null));

    ReplayProjection projection = project(entries);

    assertThat(projection.divergences())
        .extracting(ReplayDivergence::type)
        .containsExactly(ReplayDivergenceType.EXTERNAL_RESULT_MISSING);
    assertThat(projection.state().nodeStatus("hook")).contains(NodeExecutionStatus.COMPLETED);
  }

  @Test
  void externalCallUsesOnlyTheRecordedResult() {
    List<ReplayLogEntry> entries =
        List.of(
            ReplayTestFixtures.entry(1, "INSTANCE_STARTED", null, null, "started"),
            ReplayTestFixtures.entry(
                2, "NODE_COMPLETED", "hook", "WEBHOOK", "{\"status\":204,\"body\":\"ok\"}"));

    ReplayProjection projection = project(entries);

    assertThat(projection.divergences()).isEmpty();
    assertThat(projection.state().outputSummaries().get("hook")).contains("keys=body,status");
  }

  @Test
  void nodeActionWithoutNodeIdFailsClosed() {
    ReplayProjector projector = newProjector();
    boolean continued =
        projector.apply(ReplayTestFixtures.entry(1, "NODE_COMPLETED", null, "TASK", null));

    assertThat(continued).isFalse();
    assertThat(projector.result().divergences())
        .extracting(ReplayDivergence::type)
        .containsExactly(ReplayDivergenceType.MALFORMED_LOG);
  }

  @Test
  void sameFixtureProducesTheSameStateHashOneHundredTimes() {
    Set<String> hashes = new HashSet<>();
    for (int run = 0; run < 100; run++) {
      hashes.add(project(ReplayTestFixtures.happyPath()).stateHash());
    }

    assertThat(hashes).hasSize(1);
  }

  @Test
  void oneThousandRowsAreFoldedWellUnderTwoSeconds() {
    List<ReplayLogEntry> entries = new ArrayList<>();
    entries.add(ReplayTestFixtures.entry(1, "INSTANCE_STARTED", null, null, "started"));
    for (int i = 0; i < 1_000; i++) {
      entries.add(ReplayTestFixtures.entry(i + 2, "NODE_COMPLETED", "n" + i, "TASK", null));
    }

    long startedAt = System.nanoTime();
    ReplayProjection projection = project(entries);
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(projection.failedClosed()).isFalse();
    assertThat(projection.appliedRows()).isEqualTo(1_001);
    assertThat(projection.state().nodeStatuses()).hasSize(1_000);
    assertThat(elapsedMs).isLessThan(2_000);
  }

  @Test
  void dictionaryIsExplicitAndRejectsFutureActions() {
    assertThat(ReplayAction.tokens())
        .contains(
            "INSTANCE_STARTED",
            "NODE_COMPLETED",
            "NODE_FORKED",
            "NODE_MERGED",
            "NODE_FAILED",
            "WORKFLOW_COMPLETED");
    assertThat(ReplayAction.fromToken("SOMETHING_NEW")).isEmpty();
    assertThat(ReplayAction.fromToken(null)).isEmpty();
  }

  @Test
  void missingNodeExecutionIsReported() {
    ReplayProjection projection = project(ReplayTestFixtures.happyPath());
    PersistedReplaySnapshot persisted =
        new PersistedReplaySnapshot(
            WorkflowState.COMPLETED, Map.of("ghost", NodeExecutionStatus.COMPLETED), Map.of());

    List<ReplayDivergence> divergences = ReplayComparison.compare(projection.state(), persisted);

    assertThat(divergences)
        .extracting(ReplayDivergence::type)
        .contains(ReplayDivergenceType.NODE_NOT_PERSISTED);
  }

  @Test
  void unknownUuidIsStableInHash() {
    UUID instanceId = ReplayTestFixtures.INSTANCE;
    ReplayState state = ReplayState.initial(instanceId);

    assertThat(state.instanceState()).isEqualTo(WorkflowState.CREATED);
    assertThat(state.stateHash()).isEqualTo(ReplayState.initial(instanceId).stateHash());
    assertThat(state.currentNodeId()).isEmpty();
  }
}
