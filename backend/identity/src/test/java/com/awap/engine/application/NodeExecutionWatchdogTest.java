package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real-duration tests for the node timeout watchdog: no sleeps are stubbed, all assertions use
 * measured values.
 */
class NodeExecutionWatchdogTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final NodeTimeoutMetrics metrics = new NodeTimeoutMetrics(registry);
  private final List<NodeExecutionWatchdog> watchdogs = new ArrayList<>();
  private final List<ExecutorService> executors = new ArrayList<>();

  private NodeExecutionWatchdog watchdog(NodeTimeoutSettings settings) {
    NodeExecutionWatchdog watchdog =
        new NodeExecutionWatchdog(
            settings, metrics, Duration.ofMillis(100), Duration.ofMillis(100));
    watchdogs.add(watchdog);
    return watchdog;
  }

  private ExecutorService executor(int threads) {
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    executors.add(executor);
    return executor;
  }

  @AfterEach
  void tearDown() {
    executors.forEach(ExecutorService::shutdownNow);
    watchdogs.forEach(NodeExecutionWatchdog::shutdown);
    Thread.interrupted();
  }

  private static NodeTimeoutRequest request(
      String nodeType, NodeTimeoutEnforcement enforcement, Duration timeout) {
    return new NodeTimeoutRequest(nodeType, "node-1", enforcement, timeout);
  }

  private static void awaitUntil(BooleanSupplier condition, Duration limit) {
    long deadline = System.nanoTime() + limit.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static long workerThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().startsWith(NodeExecutionWatchdog.THREAD_NAME_PREFIX))
        .count();
  }

  @Test
  void blockingWorkWithOneSecondBudgetIsAbandonedWithinTolerance() {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));
    CountDownLatch release = new CountDownLatch(1);

    long start = System.nanoTime();
    NodeTimeoutException failure =
        catchThrowableOfType(
            () ->
                watchdog.run(
                    request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1)),
                    () -> {
                      release.await(5, TimeUnit.SECONDS);
                      return "late";
                    }),
            NodeTimeoutException.class);
    Duration wallClock = Duration.ofNanos(System.nanoTime() - start);
    release.countDown();

    assertThat(failure).isNotNull();
    assertThat(failure.reason()).isEqualTo(NodeTimeoutException.REASON_TIMEOUT);
    assertThat(wallClock).isBetween(Duration.ofSeconds(1), Duration.ofMillis(1500));
    assertThat(failure.elapsed()).isBetween(Duration.ofSeconds(1), Duration.ofMillis(1500));
    assertThat(failure.timeout()).isEqualTo(Duration.ofSeconds(1));

    assertThat(
            registry
                .get(NodeTimeoutMetrics.TIMEOUT)
                .tag("type", "WEBHOOK")
                .tag("enforcement", "ENFORCED")
                .tag("reason", "enforced")
                .counter()
                .count())
        .isEqualTo(1.0d);
    Timer duration = registry.get(NodeTimeoutMetrics.DURATION).tag("type", "WEBHOOK").timer();
    assertThat(duration.count()).isEqualTo(1L);
    assertThat(duration.totalTime(TimeUnit.MILLISECONDS)).isBetween(1000.0d, 1500.0d);
  }

  @Test
  void fastWorkDoesNotTimeOut() {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));

    String value =
        watchdog.run(
            request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1)),
            () -> {
              Thread.sleep(200);
              return "ok";
            });

    assertThat(value).isEqualTo("ok");
    Timer duration = registry.get(NodeTimeoutMetrics.DURATION).tag("type", "WEBHOOK").timer();
    assertThat(duration.count()).isEqualTo(1L);
    assertThat(duration.totalTime(TimeUnit.MILLISECONDS)).isBetween(150.0d, 900.0d);
    assertThat(registry.find(NodeTimeoutMetrics.TIMEOUT).counter()).isNull();
  }

  @Test
  void interruptFlagIsClearedAndTheNextNodeExecutesNormally() {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));

    catchThrowableOfType(
        () ->
            watchdog.run(
                request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1)),
                () -> {
                  new CountDownLatch(1).await();
                  return "never";
                }),
        NodeTimeoutException.class);

    assertThat(Thread.currentThread().isInterrupted()).isFalse();

    String next =
        watchdog.run(
            request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1)),
            () -> "next-node-ok");

    assertThat(next).isEqualTo("next-node-ok");
  }

  @Test
  void interruptingTheWaitingThreadIsPreservedAndNeverSwallowed() {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));
    CountDownLatch release = new CountDownLatch(1);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(
              () ->
                  watchdog.run(
                      request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(5)),
                      () -> {
                        release.await(5, TimeUnit.SECONDS);
                        return "late";
                      }))
          .isInstanceOf(NodeInterruptedExecutionException.class)
          .hasMessageContaining("was interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      release.countDown();
      Thread.interrupted();
    }
  }

  @Test
  void boundedExecutorSurvivesFiftyConcurrentTimeouts() throws Exception {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 4, 46));
    ExecutorService callers = executor(50);
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    NodeTimeoutRequest request =
        request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofMillis(200));

    List<Future<NodeTimeoutException>> outcomes = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      outcomes.add(
          callers.submit(
              () -> {
                startGate.await();
                return catchThrowableOfType(
                    () ->
                        watchdog.run(
                            request,
                            () -> {
                              release.await(5, TimeUnit.SECONDS);
                              return "late";
                            }),
                    NodeTimeoutException.class);
              }));
    }
    startGate.countDown();

    for (Future<NodeTimeoutException> outcome : outcomes) {
      NodeTimeoutException failure = outcome.get(30, TimeUnit.SECONDS);
      assertThat(failure).isNotNull();
      assertThat(failure.timeout()).isEqualTo(Duration.ofMillis(200));
      assertThat(failure.elapsed()).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }
    release.countDown();

    assertThat(
            registry
                .get(NodeTimeoutMetrics.TIMEOUT)
                .tag("type", "WEBHOOK")
                .tag("reason", "enforced")
                .counter()
                .count())
        .isEqualTo(50.0d);
    assertThat(registry.find(NodeTimeoutMetrics.REJECTED).counter()).isNull();
  }

  @Test
  void saturatedExecutorRejectsNewWorkInAControlledWay() throws Exception {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 1, 0));
    ExecutorService callers = executor(2);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Future<NodeTimeoutException> running =
        callers.submit(
            () ->
                catchThrowableOfType(
                    () ->
                        watchdog.run(
                            request(
                                "WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(2)),
                            () -> {
                              started.countDown();
                              release.await(5, TimeUnit.SECONDS);
                              return "late";
                            }),
                    NodeTimeoutException.class));
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    Future<NodeTimeoutException> queued =
        callers.submit(
            () ->
                catchThrowableOfType(
                    () ->
                        watchdog.run(
                            request(
                                "WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofMillis(300)),
                            () -> {
                              release.await(5, TimeUnit.SECONDS);
                              return "late";
                            }),
                    NodeTimeoutException.class));
    awaitUntil(() -> watchdog.activeWorkerCount() == 1, Duration.ofSeconds(1));
    Thread.sleep(100);

    assertThatThrownBy(
            () ->
                watchdog.run(
                    request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofMillis(100)),
                    () -> "never"))
        .isInstanceOf(NodeTimeoutRejectedException.class)
        .hasMessageContaining("bounded node timeout executor is saturated");

    NodeTimeoutException firstOutcome = running.get(10, TimeUnit.SECONDS);
    NodeTimeoutException secondOutcome = queued.get(10, TimeUnit.SECONDS);
    release.countDown();
    assertThat(firstOutcome).isNotNull();
    assertThat(firstOutcome.timeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(secondOutcome).isNotNull();
    assertThat(secondOutcome.timeout()).isEqualTo(Duration.ofMillis(300));
    assertThat(registry.get(NodeTimeoutMetrics.REJECTED).tag("type", "WEBHOOK").counter().count())
        .isEqualTo(1.0d);
  }

  @Test
  void shutdownDuringAnInFlightTimeoutDrainsWithoutLeakingThreads() throws Exception {
    long before = workerThreads();
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService caller = executor(1);

    Future<NodeTimeoutException> inFlight =
        caller.submit(
            () ->
                catchThrowableOfType(
                    () ->
                        watchdog.run(
                            request(
                                "WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(10)),
                            () -> {
                              release.await(10, TimeUnit.SECONDS);
                              return "late";
                            }),
                    NodeTimeoutException.class));
    awaitUntil(() -> workerThreads() > before, Duration.ofSeconds(2));

    watchdog.shutdown();

    NodeTimeoutException failure = inFlight.get(10, TimeUnit.SECONDS);
    release.countDown();
    assertThat(failure).isNotNull();
    assertThat(failure.reason()).isEqualTo(NodeTimeoutException.REASON_CANCELLED);

    awaitUntil(() -> workerThreads() <= before, Duration.ofSeconds(5));
    assertThat(workerThreads() - before).isLessThanOrEqualTo(2);
  }

  @Test
  void workerThatSwallowsInterruptsStaysVisibleInTheAbandonedGauge() {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));
    AtomicBoolean finished = new AtomicBoolean();

    NodeTimeoutException failure =
        catchThrowableOfType(
            () ->
                watchdog.run(
                    request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofMillis(100)),
                    () -> {
                      long deadline = System.nanoTime() + Duration.ofMillis(600).toNanos();
                      while (System.nanoTime() < deadline) {
                        try {
                          Thread.sleep(20);
                        } catch (InterruptedException swallowedOnPurpose) {
                          // a badly behaved node: the interrupt is swallowed
                        }
                      }
                      finished.set(true);
                      return "done";
                    }),
            NodeTimeoutException.class);

    assertThat(failure).isNotNull();
    assertThat(watchdog.abandonedWorkers()).isEqualTo(1);
    assertThat(registry.get(NodeTimeoutMetrics.ABANDONED).gauge().value()).isEqualTo(1.0d);

    awaitUntil(() -> watchdog.abandonedWorkers() == 0, Duration.ofSeconds(3));
    assertThat(finished).isTrue();
    assertThat(registry.get(NodeTimeoutMetrics.ABANDONED).gauge().value()).isZero();
  }

  @Test
  void negativeDurationsAreImpossible() {
    assertThat(NodeExecutionWatchdog.elapsedNanos(5_000L, 1_000L)).isZero();

    metrics.recordDuration("WEBHOOK", Duration.ofMillis(-10));

    Timer timer = registry.get(NodeTimeoutMetrics.DURATION).tag("type", "WEBHOOK").timer();
    assertThat(timer.count()).isEqualTo(1L);
    assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isZero();
  }

  @Test
  void unsupportedNodeTypesSkipMeasurementAndTimeout() {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));

    String value =
        watchdog.run(
            request("START", NodeTimeoutEnforcement.UNSUPPORTED, Duration.ofMillis(1)),
            () -> {
              Thread.sleep(50);
              return "ok";
            });

    assertThat(value).isEqualTo("ok");
    assertThat(registry.find(NodeTimeoutMetrics.DURATION).timer()).isNull();
    assertThat(registry.find(NodeTimeoutMetrics.TIMEOUT).counter()).isNull();
  }

  @Test
  void bestEffortBreachIsReportedOnlyAfterTheWorkReturns() {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));
    AtomicBoolean workFinished = new AtomicBoolean();

    NodeTimeoutException failure =
        catchThrowableOfType(
            () ->
                watchdog.run(
                    request("FORM", NodeTimeoutEnforcement.BEST_EFFORT, Duration.ofMillis(100)),
                    () -> {
                      Thread.sleep(250);
                      workFinished.set(true);
                      return "committed";
                    }),
            NodeTimeoutException.class);

    assertThat(failure).isNotNull();
    assertThat(workFinished).isTrue();
    assertThat(failure.elapsed()).isGreaterThanOrEqualTo(Duration.ofMillis(250));
    assertThat(
            registry
                .get(NodeTimeoutMetrics.TIMEOUT)
                .tag("type", "FORM")
                .tag("enforcement", "BEST_EFFORT")
                .tag("reason", "best_effort")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void workFailuresPropagateUnchangedAndAreMeasured() {
    NodeExecutionWatchdog watchdog = watchdog(new NodeTimeoutSettings(30, 3600, 2, 8));
    Callable<String> failing =
        () -> {
          throw new IllegalStateException("boom");
        };

    assertThatThrownBy(
            () ->
                watchdog.run(
                    request("WEBHOOK", NodeTimeoutEnforcement.BEST_EFFORT, Duration.ofSeconds(1)),
                    failing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
    assertThatThrownBy(
            () ->
                watchdog.run(
                    request("WEBHOOK", NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1)),
                    failing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    assertThat(registry.get(NodeTimeoutMetrics.DURATION).tag("type", "WEBHOOK").timer().count())
        .isEqualTo(2L);
  }
}
