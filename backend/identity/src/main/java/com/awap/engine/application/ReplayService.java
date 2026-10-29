package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.PersistedReplaySnapshot;
import com.awap.engine.domain.ReplayComparison;
import com.awap.engine.domain.ReplayDivergence;
import com.awap.engine.domain.ReplayLogEntry;
import com.awap.engine.domain.ReplayLogOrder;
import com.awap.engine.domain.ReplayOutputSummarizer;
import com.awap.engine.domain.ReplayProjection;
import com.awap.engine.domain.ReplayProjector;
import com.awap.engine.domain.ReplayRun;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.engine.interfaces.dto.ReplayReport;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Deterministic replay of a workflow instance from its execution log.
 *
 * <p>Dry-run projects the log, compares the projection with the persisted {@code
 * workflow_instances}/{@code node_executions} rows and returns a report with zero side effects (no
 * outbox, instance or node write). A real replay performs the same verification, always writes a
 * {@code replay_runs} audit row, is guarded by a per-instance lock plus the instance's optimistic
 * version, and only then delegates to the legacy durable re-drive. Divergences never mutate state:
 * they raise {@code REPLAY_DIVERGENCE} and increment the divergence metric/alert.
 */
@Service
public class ReplayService {

  private static final int DIVERGENCE_DETAIL_LIMIT = 4000;

  private final WorkflowInstanceRepository instanceRepository;
  private final ExecutionLogRepository executionLogRepository;
  private final NodeExecutionRepository nodeExecutionRepository;
  private final WorkflowEngineService engineService;
  private final ReplayAuditWriter auditWriter;
  private final ReplayOutputSummarizer summarizer;
  private final ReplaySettings settings;
  private final ReplayMetrics metrics;
  private final ReplayLockRegistry lockRegistry;
  private final Clock clock;

  @Autowired
  public ReplayService(
      WorkflowInstanceRepository instanceRepository,
      ExecutionLogRepository executionLogRepository,
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowEngineService engineService,
      ReplayAuditWriter auditWriter,
      ReplayOutputSummarizer summarizer,
      ReplaySettings settings,
      ReplayMetrics metrics,
      ReplayLockRegistry lockRegistry) {
    this(
        instanceRepository,
        executionLogRepository,
        nodeExecutionRepository,
        engineService,
        auditWriter,
        summarizer,
        settings,
        metrics,
        lockRegistry,
        Clock.systemUTC());
  }

  ReplayService(
      WorkflowInstanceRepository instanceRepository,
      ExecutionLogRepository executionLogRepository,
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowEngineService engineService,
      ReplayAuditWriter auditWriter,
      ReplayOutputSummarizer summarizer,
      ReplaySettings settings,
      ReplayMetrics metrics,
      ReplayLockRegistry lockRegistry,
      Clock clock) {
    this.instanceRepository = instanceRepository;
    this.executionLogRepository = executionLogRepository;
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.engineService = engineService;
    this.auditWriter = auditWriter;
    this.summarizer = summarizer;
    this.settings = settings;
    this.metrics = metrics;
    this.lockRegistry = lockRegistry;
    this.clock = clock;
  }

  /** Projects the log and returns the comparison report without touching any persisted state. */
  public ReplayReport dryRun(UUID tenantId, UUID instanceId, Optional<UUID> requestedBy) {
    WorkflowInstance instance = find(tenantId, instanceId);
    ReplayExecution execution = project(tenantId, instance);
    audit(tenantId, instanceId, requestedBy, execution, true);
    metrics.recordRun(
        execution.result(),
        true,
        Duration.ofMillis(execution.durationMs()),
        execution.divergences());
    return ReplayReport.from(
        instanceId,
        true,
        execution.projection(),
        execution.divergences(),
        execution.rows(),
        execution.pages(),
        execution.durationMs());
  }

  /** Verifies, audits and then performs the durable replay; 409 on divergence or concurrency. */
  public WorkflowInstanceResponse replay(
      UUID tenantId, UUID instanceId, Optional<UUID> requestedBy) {
    if (!lockRegistry.tryAcquire(instanceId)) {
      metrics.recordLimit("concurrent-replay");
      throw new AwapException(
          ErrorCode.REPLAY_CONFLICT,
          HttpStatus.CONFLICT,
          "A replay is already running for this workflow instance.");
    }
    try {
      WorkflowInstance instance = find(tenantId, instanceId);
      long expectedVersion = instance.getVersion();
      ReplayExecution execution = project(tenantId, instance);
      audit(tenantId, instanceId, requestedBy, execution, false);
      metrics.recordRun(
          execution.result(),
          false,
          Duration.ofMillis(execution.durationMs()),
          execution.divergences());
      if (execution.diverged()) {
        throw new AwapException(
            ErrorCode.REPLAY_DIVERGENCE,
            HttpStatus.CONFLICT,
            "Replay diverged from the persisted state: " + detail(execution.divergences()));
      }
      assertVersionUnchanged(tenantId, instanceId, expectedVersion);
      try {
        return engineService.replay(tenantId, instanceId);
      } catch (OptimisticLockingFailureException ex) {
        metrics.recordLimit("optimistic-lock");
        throw new AwapException(
            ErrorCode.REPLAY_CONFLICT,
            HttpStatus.CONFLICT,
            "The workflow instance was modified concurrently; retry the replay.");
      }
    } finally {
      lockRegistry.release(instanceId);
    }
  }

  private ReplayExecution project(UUID tenantId, WorkflowInstance instance) {
    long startedNanos = System.nanoTime();
    Instant deadline = Instant.now(clock).plus(settings.timeout());
    ReplayProjector projector = new ReplayProjector(instance.getId(), summarizer);
    int pages = 0;
    int rows = 0;
    boolean stopped = false;
    try {
      while (!stopped) {
        Page<ExecutionLog> batch =
            executionLogRepository.findReplayPage(
                tenantId,
                instance.getId(),
                PageRequest.of(
                    pages,
                    settings.pageSize(),
                    Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
        if (batch.isEmpty()) {
          break;
        }
        List<ReplayLogEntry> entries =
            batch.getContent().stream()
                .map(ReplayLogEntry::of)
                .sorted(ReplayLogOrder.INSTANCE)
                .toList();
        for (ReplayLogEntry entry : entries) {
          rows++;
          if (rows > settings.maxRows()) {
            metrics.recordLimit("max-rows");
            throw new AwapException(
                ErrorCode.REPLAY_LOG_TOO_LARGE,
                HttpStatus.CONTENT_TOO_LARGE,
                "Execution log exceeds the replay row limit of "
                    + settings.maxRows()
                    + " rows; raise awap.replay.max-rows to replay this instance.");
          }
          if (Instant.now(clock).isAfter(deadline)) {
            metrics.recordLimit("timeout");
            throw new AwapException(
                ErrorCode.REPLAY_TIMEOUT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Execution log replay exceeded the timeout of "
                    + settings.timeout()
                    + "; raise awap.replay.timeout to replay this instance.");
          }
          if (!projector.apply(entry)) {
            stopped = true;
            break;
          }
        }
        pages++;
        if (stopped || !batch.hasNext()) {
          break;
        }
      }
    } catch (DataAccessException ex) {
      metrics.recordLimit("log-unavailable");
      throw new AwapException(
          ErrorCode.REPLAY_LOG_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          "The execution log could not be read; no state was changed.");
    }

    ReplayProjection projection = projector.result();
    List<ReplayDivergence> divergences = new ArrayList<>(projection.divergences());
    divergences.addAll(ReplayComparison.compare(projection.state(), snapshot(instance)));
    long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    return new ReplayExecution(projection, List.copyOf(divergences), rows, pages, durationMs);
  }

  private PersistedReplaySnapshot snapshot(WorkflowInstance instance) {
    Map<String, NodeExecutionStatus> statuses = new LinkedHashMap<>();
    Map<String, PersistedReplaySnapshot.BranchCounters> counters = new LinkedHashMap<>();
    for (NodeExecution execution :
        nodeExecutionRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(instance.getId())) {
      statuses.put(execution.getNodeId(), execution.getStatus());
      if (execution.getBranchTokenCount() > 0 || execution.getMergedBranchCount() > 0) {
        counters.put(
            execution.getNodeId(),
            new PersistedReplaySnapshot.BranchCounters(
                execution.getBranchTokenCount(), execution.getMergedBranchCount()));
      }
    }
    return new PersistedReplaySnapshot(instance.getState(), statuses, counters);
  }

  private void audit(
      UUID tenantId,
      UUID instanceId,
      Optional<UUID> requestedBy,
      ReplayExecution execution,
      boolean dryRun) {
    ReplayRun run =
        new ReplayRun(
            tenantId,
            instanceId,
            dryRun,
            execution.result(),
            execution.diverged(),
            execution.divergences().size(),
            detail(execution.divergences()),
            execution.projection().stateHash(),
            execution.rows(),
            execution.pages(),
            execution.durationMs(),
            requestedBy.orElse(null));
    auditWriter.record(run);
  }

  private void assertVersionUnchanged(UUID tenantId, UUID instanceId, long expectedVersion) {
    if (find(tenantId, instanceId).getVersion() != expectedVersion) {
      metrics.recordLimit("optimistic-lock");
      throw new AwapException(
          ErrorCode.REPLAY_CONFLICT,
          HttpStatus.CONFLICT,
          "The workflow instance changed during replay; retry the replay.");
    }
  }

  private WorkflowInstance find(UUID tenantId, UUID id) {
    return instanceRepository
        .findByIdAndTenantIdAndDeletedFalse(id, tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "Workflow instance not found."));
  }

  private static String detail(List<ReplayDivergence> divergences) {
    String joined =
        divergences.stream()
            .map(
                divergence ->
                    divergence.type().name()
                        + divergence.nodeId().map(node -> "@" + node).orElse(""))
            .collect(Collectors.joining(","));
    return joined.length() <= DIVERGENCE_DETAIL_LIMIT
        ? joined
        : joined.substring(0, DIVERGENCE_DETAIL_LIMIT);
  }

  /** Result of one projection + comparison pass. */
  record ReplayExecution(
      ReplayProjection projection,
      List<ReplayDivergence> divergences,
      int rows,
      int pages,
      long durationMs) {

    boolean diverged() {
      return projection.failedClosed() || !divergences.isEmpty();
    }

    String result() {
      return diverged() ? "DIVERGED" : "MATCHED";
    }
  }
}
