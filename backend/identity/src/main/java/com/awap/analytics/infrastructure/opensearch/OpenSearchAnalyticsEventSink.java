package com.awap.analytics.infrastructure.opensearch;

import com.awap.analytics.application.AnalyticsEventSink;
import com.awap.analytics.application.AnalyticsIndexNames;
import com.awap.analytics.application.AnalyticsPiiRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Hardened raw analytics sink for OpenSearch.
 *
 * <p>Events are redacted through an allowlist, buffered in a bounded queue and written either as a
 * single deterministic upsert (low volume) or as one {@code _bulk} request. Retryable failures use
 * the configured 1s/5s/25s backoff, a circuit breaker sheds load while OpenSearch is unavailable,
 * and mapping conflicts are routed once to a dead-letter index instead of being retried forever.
 *
 * <p>PostgreSQL aggregates written by {@link com.awap.analytics.application.AnalyticsService}
 * remain the authoritative analytics source; this sink is a best-effort raw-event mirror.
 */
@Component
@ConditionalOnProperty(name = "awap.analytics.opensearch.enabled", havingValue = "true")
public class OpenSearchAnalyticsEventSink implements AnalyticsEventSink, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(OpenSearchAnalyticsEventSink.class);
  private static final int MAX_ERROR_LENGTH = 500;

  private final OpenSearchProperties properties;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final OpenSearchTransport transport;
  private final AnalyticsPiiRedactor redactor;
  private final AnalyticsIndexNames indexNames;
  private final SinkCircuitBreaker circuitBreaker;
  private final ScheduledExecutorService executor;

  private final Object queueLock = new Object();
  private final ArrayDeque<PendingDocument> queue = new ArrayDeque<>();
  private final Object flushLock = new Object();

  private volatile boolean closed;

  @Autowired
  public OpenSearchAnalyticsEventSink(
      OpenSearchProperties properties,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      OpenSearchTransport transport) {
    this(properties, objectMapper, meterRegistry, transport, System::nanoTime, newScheduler());
  }

  OpenSearchAnalyticsEventSink(
      OpenSearchProperties properties,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      OpenSearchTransport transport,
      LongSupplier nanoClock,
      ScheduledExecutorService executor) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.transport = transport;
    this.redactor = new AnalyticsPiiRedactor();
    this.indexNames = new AnalyticsIndexNames(properties.index(), properties.dlqIndex());
    this.circuitBreaker =
        new SinkCircuitBreaker(
            properties.breakerFailureThreshold(), properties.breakerOpenMs(), nanoClock);
    this.executor = executor;
    Gauge.builder("awap.analytics.sink.queue.depth", this, OpenSearchAnalyticsEventSink::queueDepth)
        .description("Analytics events waiting to be written to OpenSearch")
        .register(meterRegistry);
    Gauge.builder("awap.analytics.sink.circuit.open", this, sink -> sink.isCircuitOpen() ? 1d : 0d)
        .description("1 when the OpenSearch sink circuit breaker is open")
        .register(meterRegistry);
    executor.scheduleWithFixedDelay(
        this::flushSafely,
        properties.flushIntervalMs(),
        properties.flushIntervalMs(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void write(Map<String, Object> document) {
    if (closed) {
      drop("closed");
      return;
    }
    Map<String, Object> redacted = redactor.redact(document);
    Optional<Instant> timestamp = parseTimestamp(redacted.get("timestamp"));
    if (!hasText(redacted.get("tenantId"))
        || !hasText(redacted.get("eventType"))
        || timestamp.isEmpty()) {
      deadLetter(redacted, "malformed", "missing or invalid required analytics fields");
      return;
    }
    if (!circuitBreaker.tryAcquire()) {
      drop("circuit_open");
      return;
    }
    enqueue(new PendingDocument(redacted, timestamp.get(), 0));
  }

  /** Drains the queue synchronously. Used by tests and by graceful shutdown paths. */
  public void flushNow() {
    flush();
  }

  public int queueDepth() {
    synchronized (queueLock) {
      return queue.size();
    }
  }

  public boolean isCircuitOpen() {
    return circuitBreaker.state() != SinkCircuitBreaker.State.CLOSED;
  }

  public SinkCircuitBreaker.State circuitState() {
    return circuitBreaker.state();
  }

  private void enqueue(PendingDocument document) {
    boolean shouldFlush;
    synchronized (queueLock) {
      if (queue.size() >= properties.queueCapacity()) {
        PendingDocument oldest = queue.pollFirst();
        if (oldest != null) {
          drop("queue_overflow");
        }
      }
      queue.addLast(document);
      shouldFlush = queue.size() >= properties.batchSize();
    }
    if (shouldFlush) {
      execute(this::flushSafely);
    }
  }

  private void flushSafely() {
    if (closed) {
      return;
    }
    try {
      flush();
    } catch (RuntimeException ex) {
      increment("awap.analytics.sink.events", "outcome", "failure");
      log.warn("OpenSearch analytics flush failed: {}", ex.getMessage());
    }
  }

  private void flush() {
    synchronized (flushLock) {
      List<PendingDocument> batch = pollBatch();
      if (batch.isEmpty()) {
        return;
      }
      if (batch.size() == 1) {
        sendSingle(batch.getFirst());
      } else {
        sendBulk(batch);
      }
    }
  }

  private List<PendingDocument> pollBatch() {
    synchronized (queueLock) {
      int size = Math.min(properties.batchSize(), queue.size());
      List<PendingDocument> batch = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        batch.add(queue.pollFirst());
      }
      return batch;
    }
  }

  private void sendSingle(PendingDocument pending) {
    String index = indexNames.dailyIndex(pending.timestamp());
    try {
      String body = objectMapper.writeValueAsString(pending.document());
      OpenSearchResponse response =
          transport.execute("PUT", "/" + index + "/_doc/" + documentId(pending.document()), body);
      if (response.successful()) {
        success(1);
        circuitBreaker.recordSuccess();
        return;
      }
      if (isRetryable(response.status())) {
        circuitBreaker.recordFailure();
        retry(pending, "http_" + response.status());
      } else if (isMappingConflict(response.body())) {
        deadLetter(pending.document(), "mapping_conflict", response.body());
      } else {
        deadLetter(pending.document(), "rejected", response.body());
      }
    } catch (JsonProcessingException ex) {
      deadLetter(pending.document(), "malformed", ex.getOriginalMessage());
    } catch (HttpTimeoutException | SocketTimeoutException ex) {
      circuitBreaker.recordFailure();
      retry(pending, "timeout");
    } catch (IOException ex) {
      circuitBreaker.recordFailure();
      retry(pending, "io_error");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      circuitBreaker.recordFailure();
      increment("awap.analytics.sink.events", "outcome", "failure");
    }
  }

  private void sendBulk(List<PendingDocument> batch) {
    String payload;
    try {
      payload = bulkPayload(batch);
    } catch (JsonProcessingException ex) {
      for (PendingDocument pending : batch) {
        deadLetter(pending.document(), "malformed", ex.getOriginalMessage());
      }
      return;
    }
    try {
      OpenSearchResponse response = transport.execute("POST", "/_bulk", payload);
      if (response.successful()) {
        handleBulkResponse(response.body(), batch);
      } else if (isRetryable(response.status())) {
        circuitBreaker.recordFailure();
        batch.forEach(pending -> retry(pending, "http_" + response.status()));
      } else if (isMappingConflict(response.body())) {
        batch.forEach(
            pending -> deadLetter(pending.document(), "mapping_conflict", response.body()));
      } else {
        batch.forEach(pending -> deadLetter(pending.document(), "rejected", response.body()));
      }
    } catch (HttpTimeoutException | SocketTimeoutException ex) {
      circuitBreaker.recordFailure();
      batch.forEach(pending -> retry(pending, "timeout"));
    } catch (IOException ex) {
      circuitBreaker.recordFailure();
      batch.forEach(pending -> retry(pending, "io_error"));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      circuitBreaker.recordFailure();
      increment("awap.analytics.sink.events", "outcome", "failure");
    }
  }

  private void handleBulkResponse(String body, List<PendingDocument> batch) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (JsonProcessingException ex) {
      circuitBreaker.recordFailure();
      batch.forEach(pending -> retry(pending, "bulk_response_invalid"));
      return;
    }
    if (!root.path("errors").asBoolean(false)) {
      success(batch.size());
      circuitBreaker.recordSuccess();
      return;
    }
    JsonNode items = root.path("items");
    boolean anyRetryable = false;
    int successes = 0;
    for (int i = 0; i < batch.size(); i++) {
      PendingDocument pending = batch.get(i);
      JsonNode outcome = i < items.size() ? items.get(i).path("index") : null;
      if (outcome == null) {
        anyRetryable = true;
        retry(pending, "bulk_response_incomplete");
        continue;
      }
      int status = outcome.path("status").asInt(500);
      if (status >= 200 && status < 300) {
        successes++;
        continue;
      }
      String error = outcome.path("error").toString();
      if (isRetryable(status)) {
        anyRetryable = true;
        retry(pending, "http_" + status);
      } else if (isMappingConflict(error)) {
        deadLetter(pending.document(), "mapping_conflict", error);
      } else {
        deadLetter(pending.document(), "rejected", error);
      }
    }
    if (successes > 0) {
      success(successes);
    }
    if (anyRetryable) {
      circuitBreaker.recordFailure();
    } else if (successes == batch.size()) {
      circuitBreaker.recordSuccess();
    }
  }

  private String bulkPayload(List<PendingDocument> batch) throws JsonProcessingException {
    StringBuilder payload = new StringBuilder();
    for (PendingDocument pending : batch) {
      Map<String, Object> action = new LinkedHashMap<>();
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("_index", indexNames.dailyIndex(pending.timestamp()));
      metadata.put("_id", documentId(pending.document()));
      action.put("index", metadata);
      payload.append(objectMapper.writeValueAsString(action)).append('\n');
      payload.append(objectMapper.writeValueAsString(pending.document())).append('\n');
    }
    return payload.toString();
  }

  private void retry(PendingDocument pending, String reason) {
    increment("awap.analytics.sink.retries", "reason", reason);
    int nextAttempt = pending.attempt() + 1;
    if (nextAttempt >= properties.maxAttempts()) {
      increment("awap.analytics.sink.events", "outcome", "failure");
      drop("retries_exhausted");
      log.warn(
          "Dropping analytics event after {} attempts (reason={})",
          properties.maxAttempts(),
          reason);
      return;
    }
    int delayIndex = Math.min(pending.attempt(), properties.retryDelaysMs().size() - 1);
    long delayMs = properties.retryDelaysMs().get(delayIndex);
    PendingDocument next =
        new PendingDocument(pending.document(), pending.timestamp(), nextAttempt);
    execute(
        () -> {
          enqueue(next);
          flushSafely();
        },
        delayMs);
  }

  private void deadLetter(Map<String, Object> document, String reason, String error) {
    increment("awap.analytics.sink.dead_letter", "reason", reason);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("failedAt", Instant.now().toString());
    payload.put("reason", reason);
    payload.put("error", redactor.redactText(truncate(error)));
    payload.put("document", document);
    String index = indexNames.deadLetterIndex(Instant.now());
    try {
      String body = objectMapper.writeValueAsString(payload);
      OpenSearchResponse response =
          transport.execute("PUT", "/" + index + "/_doc/" + UUID.randomUUID(), body);
      if (!response.successful()) {
        increment("awap.analytics.sink.events", "outcome", "failure");
        log.warn("Dead-letter write for analytics event failed with status {}", response.status());
      }
    } catch (IOException ex) {
      increment("awap.analytics.sink.events", "outcome", "failure");
      log.warn("Dead-letter write for analytics event failed: {}", ex.getMessage());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      increment("awap.analytics.sink.events", "outcome", "failure");
    }
  }

  private void success(int count) {
    Counter counter = meterRegistry.counter("awap.analytics.sink.events", "outcome", "success");
    counter.increment(count);
  }

  private void drop(String reason) {
    increment("awap.analytics.sink.dropped", "reason", reason);
    log.debug("Dropping analytics event (reason={})", reason);
  }

  private void increment(String name, String... tags) {
    meterRegistry.counter(name, tags).increment();
  }

  private void execute(Runnable task) {
    try {
      executor.execute(task);
    } catch (RejectedExecutionException ex) {
      drop("closed");
    }
  }

  private void execute(Runnable task, long delayMs) {
    try {
      executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException ex) {
      drop("closed");
    }
  }

  private boolean hasText(Object value) {
    return value instanceof String text
        && !text.isBlank()
        && !AnalyticsPiiRedactor.REDACTED.equals(text);
  }

  private Optional<Instant> parseTimestamp(Object value) {
    if (!(value instanceof String text)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  private boolean isRetryable(int status) {
    return status == 429 || status >= 500;
  }

  private boolean isMappingConflict(String body) {
    return body.contains("mapper_parsing_exception")
        || body.contains("document_parsing_exception")
        || body.contains("strict_dynamic_mapping_exception");
  }

  private String documentId(Map<String, Object> document) {
    String identity =
        String.join(
            "|",
            String.valueOf(document.get("tenantId")),
            String.valueOf(document.get("eventType")),
            String.valueOf(document.get("workflowInstanceId")),
            String.valueOf(document.get("taskId")),
            String.valueOf(document.get("timestamp")),
            String.valueOf(document.get("durationMs")));
    return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String truncate(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
  }

  private static ScheduledExecutorService newScheduler() {
    ThreadFactory threadFactory =
        runnable -> {
          Thread thread = new Thread(runnable, "awap-opensearch-analytics-sink");
          thread.setDaemon(true);
          return thread;
        };
    return Executors.newSingleThreadScheduledExecutor(threadFactory);
  }

  @Override
  @PreDestroy
  public void close() {
    closed = true;
    executor.shutdownNow();
  }

  private record PendingDocument(Map<String, Object> document, Instant timestamp, int attempt) {}
}
