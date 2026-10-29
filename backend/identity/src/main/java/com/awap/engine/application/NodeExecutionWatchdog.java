package com.awap.engine.application;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures node execution time with {@link System#nanoTime()} and enforces the timeout budget for
 * interrupt-safe work.
 *
 * <p>Two modes are supported:
 *
 * <ul>
 *   <li>{@link NodeTimeoutEnforcement#ENFORCED}: the work is submitted to a bounded, daemon,
 *       core-timeout executor and the caller waits at most {@code timeout}. On breach the future is
 *       cancelled with {@code Future.cancel(true)}. {@code Thread.stop} is never used: it can leave
 *       locks held, corrupt shared state and break the transaction contract.
 *   <li>{@link NodeTimeoutEnforcement#BEST_EFFORT}: the work runs on the caller thread (so a JPA
 *       transaction stays intact) and the elapsed time is measured. The breach is reported only
 *       after the work actually returns.
 * </ul>
 *
 * <p>The waiting thread never inherits the worker's interrupt flag; a stale flag is cleared and
 * reported in the structured log so the next node runs normally. A worker that swallows the
 * interrupt is counted in the {@code awap.node.timeout.abandoned} gauge (thread-leak alert).
 */
public class NodeExecutionWatchdog implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(NodeExecutionWatchdog.class);

  static final String THREAD_NAME_PREFIX = "awap-node-timeout-";

  private static final int STATE_RUNNING = 0;
  private static final int STATE_ABANDONED = 1;
  private static final int STATE_FINISHED = 2;

  private final NodeTimeoutMetrics metrics;
  private final ThreadPoolExecutor executor;
  private final AtomicInteger abandonedWorkers = new AtomicInteger();
  private final AtomicLong threadSequence = new AtomicLong();
  private final Duration shutdownGrace;

  public NodeExecutionWatchdog(NodeTimeoutSettings settings, NodeTimeoutMetrics metrics) {
    this(settings, metrics, Duration.ofSeconds(30), Duration.ofSeconds(5));
  }

  NodeExecutionWatchdog(
      NodeTimeoutSettings settings,
      NodeTimeoutMetrics metrics,
      Duration keepAlive,
      Duration shutdownGrace) {
    this.metrics = metrics;
    this.shutdownGrace = shutdownGrace;
    this.executor =
        new ThreadPoolExecutor(
            settings.workerThreads(),
            settings.workerThreads(),
            keepAlive.toMillis(),
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, settings.queueCapacity())),
            this::newWorker,
            new ThreadPoolExecutor.AbortPolicy());
    // Idle workers die after the keep-alive, so a quiet JVM holds no timeout threads.
    this.executor.allowCoreThreadTimeOut(true);
    this.metrics.registerAbandonedWorkers(abandonedWorkers);
  }

  /**
   * Runs the work under the given timeout policy.
   *
   * @throws NodeTimeoutException the budget was exceeded
   * @throws NodeTimeoutRejectedException the bounded executor is saturated
   * @throws NodeInterruptedExecutionException the waiting thread was interrupted
   */
  public <T> T run(NodeTimeoutRequest request, Callable<T> work) {
    return switch (request.enforcement()) {
      case UNSUPPORTED -> executeUnmeasured(work);
      case BEST_EFFORT -> executeMeasuredInline(request, work);
      case ENFORCED -> executeOnWorker(request, work);
    };
  }

  /** Elapsed time between two {@link System#nanoTime()} samples, never negative. */
  static long elapsedNanos(long startNanos, long endNanos) {
    return Math.max(0L, endNanos - startNanos);
  }

  /** Number of workers that ignored their interrupt and are still running. */
  public int abandonedWorkers() {
    return abandonedWorkers.get();
  }

  /** Number of queued or running tasks, exposed for the saturation tests. */
  public int activeWorkerCount() {
    return executor.getActiveCount();
  }

  /** Drains the executor gracefully; after the grace period running workers are interrupted. */
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
        if (!executor.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
          log.atWarn()
              .addKeyValue("abandonedWorkers", abandonedWorkers.get())
              .log("Node timeout executor did not terminate after shutdown");
        }
      }
    } catch (InterruptedException ex) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    shutdown();
  }

  private <T> T executeUnmeasured(Callable<T> work) {
    return callWork(work);
  }

  private <T> T executeMeasuredInline(NodeTimeoutRequest request, Callable<T> work) {
    long startNanos = System.nanoTime();
    T value;
    try {
      value = callWork(work);
    } catch (RuntimeException ex) {
      metrics.recordDuration(request.nodeType(), elapsed(startNanos));
      throw ex;
    }
    Duration elapsed = elapsed(startNanos);
    metrics.recordDuration(request.nodeType(), elapsed);
    if (elapsed.compareTo(request.timeout()) > 0) {
      metrics.recordTimeout(request.nodeType(), request.enforcement(), "best_effort");
      log.atWarn()
          .addKeyValue("nodeType", request.nodeType())
          .addKeyValue("nodeId", request.nodeId())
          .addKeyValue("enforcement", request.enforcement().name())
          .addKeyValue("timeoutMs", request.timeout().toMillis())
          .addKeyValue("elapsedMs", elapsed.toMillis())
          .log("Node exceeded its timeout budget but cannot be interrupted; work already returned");
      throw new NodeTimeoutException(
          request.nodeType(), request.nodeId(), request.timeout(), elapsed);
    }
    return value;
  }

  private <T> T executeOnWorker(NodeTimeoutRequest request, Callable<T> work) {
    long startNanos = System.nanoTime();
    WatchedTask<T> task = new WatchedTask<>(work);
    Future<T> future;
    try {
      future = executor.submit(task);
    } catch (RejectedExecutionException ex) {
      metrics.recordDuration(request.nodeType(), elapsed(startNanos));
      metrics.recordRejected(request.nodeType());
      throw new NodeTimeoutRejectedException(request.nodeType(), request.nodeId(), ex);
    }

    try {
      T value = future.get(request.timeout().toNanos(), TimeUnit.NANOSECONDS);
      metrics.recordDuration(request.nodeType(), elapsed(startNanos));
      return value;
    } catch (TimeoutException ex) {
      boolean abandoned = task.markAbandoned();
      future.cancel(true);
      // The waiting thread must never inherit the worker's interrupt status.
      boolean clearedStaleInterrupt = Thread.interrupted();
      Duration elapsed = elapsed(startNanos);
      metrics.recordDuration(request.nodeType(), elapsed);
      metrics.recordTimeout(request.nodeType(), request.enforcement(), "enforced");
      log.atWarn()
          .addKeyValue("nodeType", request.nodeType())
          .addKeyValue("nodeId", request.nodeId())
          .addKeyValue("enforcement", request.enforcement().name())
          .addKeyValue("timeoutMs", request.timeout().toMillis())
          .addKeyValue("elapsedMs", elapsed.toMillis())
          .addKeyValue("workerInterrupted", abandoned)
          .addKeyValue("clearedStaleInterrupt", clearedStaleInterrupt)
          .log("Node execution abandoned after exceeding its timeout");
      throw new NodeTimeoutException(
          request.nodeType(), request.nodeId(), request.timeout(), elapsed);
    } catch (InterruptedException ex) {
      task.markAbandoned();
      future.cancel(true);
      // Preserve the interruption: the flag is restored, never swallowed.
      Thread.currentThread().interrupt();
      throw new NodeInterruptedExecutionException(request.nodeType(), request.nodeId(), ex);
    } catch (ExecutionException ex) {
      Duration elapsed = elapsed(startNanos);
      metrics.recordDuration(request.nodeType(), elapsed);
      Throwable cause = ex.getCause();
      if (cause instanceof WorkerInterruptedException
          || cause instanceof InterruptedException
          || cause instanceof java.util.concurrent.CancellationException) {
        // The worker was aborted (typically by executor shutdown): record it as a cancelled
        // timeout so the partial execution is marked instead of silently disappearing.
        metrics.recordTimeout(request.nodeType(), request.enforcement(), "cancelled");
        throw new NodeTimeoutException(
            request.nodeType(),
            request.nodeId(),
            request.timeout(),
            elapsed,
            NodeTimeoutException.REASON_CANCELLED);
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(
          "Node work of type " + request.nodeType() + " failed.", cause);
    }
  }

  private <T> T callWork(Callable<T> work) {
    try {
      return work.call();
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Node work failed.", ex);
    }
  }

  private static Duration elapsed(long startNanos) {
    return Duration.ofNanos(elapsedNanos(startNanos, System.nanoTime()));
  }

  private Thread newWorker(Runnable runnable) {
    Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + threadSequence.incrementAndGet());
    thread.setDaemon(true);
    return thread;
  }

  /**
   * Tracks whether a submitted task is still running after its future was cancelled, so a worker
   * that swallows the interrupt stays visible in the {@code abandoned} gauge.
   */
  private final class WatchedTask<T> implements Callable<T> {

    private final Callable<T> delegate;
    private final AtomicInteger state = new AtomicInteger(STATE_RUNNING);
    private final AtomicBoolean interruptedSeen = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    private WatchedTask(Callable<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public T call() {
      started.set(true);
      try {
        return delegate.call();
      } catch (InterruptedException ex) {
        interruptedSeen.set(true);
        throw new WorkerInterruptedException(ex);
      } catch (RuntimeException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new IllegalStateException("Node work failed.", ex);
      } finally {
        boolean wasRunning = state.compareAndSet(STATE_RUNNING, STATE_FINISHED);
        if (!wasRunning && state.compareAndSet(STATE_ABANDONED, STATE_FINISHED)) {
          abandonedWorkers.decrementAndGet();
        }
        // Pool threads are reused: a leftover interrupt flag would poison the next task.
        // The interrupt is observed (interruptedSeen) and deliberately cleared on the worker;
        // the caller's flag is handled separately and is never swallowed.
        Thread.interrupted();
      }
    }

    private boolean markAbandoned() {
      // A task cancelled before it ever ran will never execute its finally block, so it must not
      // be counted as an abandoned worker.
      if (!started.get()) {
        return false;
      }
      if (state.compareAndSet(STATE_RUNNING, STATE_ABANDONED)) {
        int abandoned = abandonedWorkers.incrementAndGet();
        log.atWarn()
            .addKeyValue("abandonedWorkers", abandoned)
            .addKeyValue("interruptedBeforeCancel", interruptedSeen.get())
            .log("Node timeout worker did not stop immediately after cancel; awaiting exit");
        return true;
      }
      return false;
    }
  }

  /** Internal marker so an interrupted worker is distinguishable from a genuine node failure. */
  private static final class WorkerInterruptedException extends RuntimeException {

    private WorkerInterruptedException(InterruptedException cause) {
      super("Node work was interrupted.", cause);
    }
  }
}
