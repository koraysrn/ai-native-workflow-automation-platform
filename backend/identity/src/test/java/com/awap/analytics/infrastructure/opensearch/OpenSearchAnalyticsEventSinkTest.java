package com.awap.analytics.infrastructure.opensearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenSearchAnalyticsEventSinkTest {

  private static final Instant TIMESTAMP = Instant.parse("2026-10-27T10:15:30Z");

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final FakeOpenSearchTransport transport = new FakeOpenSearchTransport();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private OpenSearchAnalyticsEventSink sink;
  private ScheduledExecutorService executor;

  @AfterEach
  void tearDown() {
    if (sink != null) {
      sink.close();
    }
  }

  private OpenSearchAnalyticsEventSink sink(OpenSearchProperties properties) {
    return sink(properties, System::nanoTime);
  }

  private OpenSearchAnalyticsEventSink sink(OpenSearchProperties properties, LongSupplier clock) {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "opensearch-sink-test");
              thread.setDaemon(true);
              return thread;
            });
    sink =
        new OpenSearchAnalyticsEventSink(
            properties, objectMapper, meters, transport, clock, executor);
    return sink;
  }

  private double counter(String name, String... tags) {
    Counter counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0d : counter.count();
  }

  @Test
  void writesSingleDocumentToDailyIndexWithExpectedFields() {
    OpenSearchAnalyticsEventSink sink = sink(OpenSearchTestSupport.properties());
    UUID tenant = UUID.randomUUID();
    UUID task = UUID.randomUUID();

    sink.write(OpenSearchTestSupport.event(tenant, "TASK_COMPLETED", task, TIMESTAMP, 123L));
    sink.flushNow();

    assertThat(transport.requests()).hasSize(1);
    FakeOpenSearchTransport.Request request = transport.requests().getFirst();
    assertThat(request.method()).isEqualTo("PUT");
    assertThat(request.path()).startsWith("/awap-analytics-2026.10.27/_doc/");
    assertThat(request.body())
        .contains("\"tenantId\":\"" + tenant + "\"")
        .contains("\"eventType\":\"TASK_COMPLETED\"")
        .contains("\"taskId\":\"" + task + "\"")
        .contains("\"durationMs\":123")
        .contains("\"timestamp\":\"2026-10-27T10:15:30Z\"");
    assertThat(counter("awap.analytics.sink.events", "outcome", "success")).isEqualTo(1d);
    assertThat(sink.queueDepth()).isZero();
  }

  @Test
  void bulkIndexesEveryBufferedDocumentInOneRequest() {
    OpenSearchAnalyticsEventSink sink =
        sink(OpenSearchTestSupport.properties(10, 10, List.of(5L), 4, 100, 1_000L, 3_600_000L));
    for (int i = 0; i < 4; i++) {
      sink.write(
          OpenSearchTestSupport.event(
              UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, i + 1L));
    }

    sink.flushNow();

    assertThat(transport.requests()).hasSize(1);
    FakeOpenSearchTransport.Request request = transport.requests().getFirst();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/_bulk");
    assertThat(countOccurrences(request.body(), "\"index\":")).isEqualTo(4);
    assertThat(countOccurrences(request.body(), "\"_index\":\"awap-analytics-2026.10.27\""))
        .isEqualTo(4);
    assertThat(counter("awap.analytics.sink.events", "outcome", "success")).isEqualTo(4d);
  }

  @Test
  void queueOverflowDropsOldestDocumentAndIncrementsMetric() {
    OpenSearchAnalyticsEventSink sink =
        sink(OpenSearchTestSupport.properties(2, 10, List.of(5L), 4, 100, 1_000L, 3_600_000L));
    UUID firstTask = UUID.randomUUID();
    UUID secondTask = UUID.randomUUID();
    UUID thirdTask = UUID.randomUUID();

    sink.write(
        OpenSearchTestSupport.event(UUID.randomUUID(), "TASK_COMPLETED", firstTask, TIMESTAMP, 1L));
    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", secondTask, TIMESTAMP, 2L));
    sink.write(
        OpenSearchTestSupport.event(UUID.randomUUID(), "TASK_COMPLETED", thirdTask, TIMESTAMP, 3L));

    assertThat(sink.queueDepth()).isEqualTo(2);
    assertThat(counter("awap.analytics.sink.dropped", "reason", "queue_overflow")).isEqualTo(1d);

    sink.flushNow();

    assertThat(transport.requests()).hasSize(1);
    String body = transport.requests().getFirst().body();
    assertThat(body).doesNotContain(firstTask.toString());
    assertThat(body).contains(secondTask.toString()).contains(thirdTask.toString());
  }

  @Test
  void retries429WithBackoffThenSucceeds() {
    OpenSearchAnalyticsEventSink sink =
        sink(OpenSearchTestSupport.properties(10, 10, List.of(60L), 3, 100, 1_000L, 3_600_000L));
    transport.enqueue(new OpenSearchResponse(429, "{}"));

    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L));
    sink.flushNow();

    OpenSearchTestSupport.await(
        () -> counter("awap.analytics.sink.events", "outcome", "success") == 1d, 3_000);
    assertThat(transport.requests()).hasSizeGreaterThanOrEqualTo(2);
    long backoffNanos =
        transport.requests().get(1).atNanos() - transport.requests().get(0).atNanos();
    assertThat(backoffNanos).isGreaterThanOrEqualTo(30_000_000L);
    assertThat(counter("awap.analytics.sink.retries", "reason", "http_429")).isEqualTo(1d);
  }

  @Test
  void retriesTimeoutThenSucceeds() {
    OpenSearchAnalyticsEventSink sink =
        sink(OpenSearchTestSupport.properties(10, 10, List.of(20L), 3, 100, 1_000L, 3_600_000L));
    transport.enqueue(new SocketTimeoutException("simulated timeout"));

    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L));
    sink.flushNow();

    OpenSearchTestSupport.await(
        () -> counter("awap.analytics.sink.events", "outcome", "success") == 1d, 3_000);
    assertThat(transport.requests()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(counter("awap.analytics.sink.retries", "reason", "timeout")).isEqualTo(1d);
  }

  @Test
  void routesMappingConflictToDeadLetterWithoutRetrying() throws InterruptedException {
    OpenSearchAnalyticsEventSink sink = sink(OpenSearchTestSupport.properties());
    transport.enqueue(
        new OpenSearchResponse(
            400,
            "{\"error\":{\"type\":\"mapper_parsing_exception\","
                + "\"reason\":\"failed to parse field [durationMs]\"}}"));
    Map<String, Object> document =
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L);
    document.put("durationMs", "not-a-number");

    sink.write(document);
    sink.flushNow();

    assertThat(transport.requests()).hasSize(2);
    FakeOpenSearchTransport.Request deadLetter = transport.requests().get(1);
    assertThat(deadLetter.method()).isEqualTo("PUT");
    assertThat(deadLetter.path()).contains("/awap-analytics-dlq-");
    assertThat(deadLetter.body())
        .contains("\"reason\":\"mapping_conflict\"")
        .contains("mapper_parsing_exception");
    assertThat(counter("awap.analytics.sink.dead_letter", "reason", "mapping_conflict"))
        .isEqualTo(1d);
    assertThat(meters.find("awap.analytics.sink.retries").counter()).isNull();

    Thread.sleep(100);
    assertThat(transport.requests()).hasSize(2);
  }

  @Test
  void malformedEventIsRejectedToDeadLetterIndex() {
    OpenSearchAnalyticsEventSink sink = sink(OpenSearchTestSupport.properties());
    Map<String, Object> malformed = new LinkedHashMap<>();
    malformed.put("eventType", "TASK_COMPLETED");
    malformed.put("timestamp", TIMESTAMP.toString());

    sink.write(malformed);
    sink.flushNow();

    assertThat(transport.requests()).hasSize(1);
    FakeOpenSearchTransport.Request deadLetter = transport.requests().getFirst();
    assertThat(deadLetter.path()).contains("/awap-analytics-dlq-");
    assertThat(deadLetter.body()).contains("\"reason\":\"malformed\"");
    assertThat(counter("awap.analytics.sink.dead_letter", "reason", "malformed")).isEqualTo(1d);
  }

  @Test
  void neverWritesPiiFieldsToTheMainIndex() {
    OpenSearchAnalyticsEventSink sink = sink(OpenSearchTestSupport.properties());
    Map<String, Object> document =
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L);
    document.put("Email", "secret@example.com");
    document.put("user", Map.of("email", "nested@example.com", "phone", "+90 555 123 45 67"));

    sink.write(document);
    sink.flushNow();

    String body = transport.requests().getFirst().body();
    assertThat(body).doesNotContain("example.com");
    assertThat(body).doesNotContain("555 123 45 67");
    assertThat(body).doesNotContain("\"Email\"").doesNotContain("\"user\"");
    assertThat(body).contains("\"tenantId\"");
  }

  @Test
  void incrementsFailureMetricWhenRetriesAreExhausted() {
    OpenSearchAnalyticsEventSink sink =
        sink(OpenSearchTestSupport.properties(10, 10, List.of(5L), 1, 100, 1_000L, 3_600_000L));
    transport.defaultResponse(new OpenSearchResponse(503, "{}"));

    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L));
    sink.flushNow();

    assertThat(counter("awap.analytics.sink.events", "outcome", "failure")).isEqualTo(1d);
    assertThat(counter("awap.analytics.sink.dropped", "reason", "retries_exhausted")).isEqualTo(1d);
  }

  @Test
  void circuitBreakerOpensAndClosesAndHealthReflectsState() throws InterruptedException {
    OpenSearchAnalyticsEventSink sink =
        sink(OpenSearchTestSupport.properties(10, 10, List.of(5L), 1, 2, 50L, 3_600_000L));
    OpenSearchSinkHealthIndicator health = new OpenSearchSinkHealthIndicator(sink);
    transport.defaultResponse(new OpenSearchResponse(503, "{}"));

    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L));
    sink.flushNow();
    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 2L));
    sink.flushNow();

    assertThat(sink.circuitState()).isEqualTo(SinkCircuitBreaker.State.OPEN);
    assertThat(meters.find("awap.analytics.sink.circuit.open").gauge().value()).isEqualTo(1d);
    assertThat(health.health().getStatus().getCode()).isEqualTo("DEGRADED");

    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 3L));
    assertThat(counter("awap.analytics.sink.dropped", "reason", "circuit_open")).isEqualTo(1d);

    Thread.sleep(80);
    transport.defaultResponse(new OpenSearchResponse(200, "{\"errors\":false}"));
    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 4L));
    sink.flushNow();

    assertThat(sink.circuitState()).isEqualTo(SinkCircuitBreaker.State.CLOSED);
    assertThat(meters.find("awap.analytics.sink.circuit.open").gauge().value()).isZero();
    assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
    assertThat(counter("awap.analytics.sink.events", "outcome", "success")).isEqualTo(1d);
  }

  @Test
  void routesOnlyMappingFailureItemsOfABulkToDeadLetter() {
    OpenSearchAnalyticsEventSink sink =
        sink(OpenSearchTestSupport.properties(10, 10, List.of(5L), 4, 100, 1_000L, 3_600_000L));
    transport.enqueue(
        new OpenSearchResponse(
            200,
            "{\"errors\":true,\"items\":["
                + "{\"index\":{\"status\":201}},"
                + "{\"index\":{\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\"}}}]}"));
    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L));
    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 2L));

    sink.flushNow();

    assertThat(transport.requests()).hasSize(2);
    assertThat(transport.requests().getFirst().path()).isEqualTo("/_bulk");
    assertThat(transport.requests().get(1).path()).contains("/awap-analytics-dlq-");
    assertThat(counter("awap.analytics.sink.events", "outcome", "success")).isEqualTo(1d);
    assertThat(counter("awap.analytics.sink.dead_letter", "reason", "mapping_conflict"))
        .isEqualTo(1d);
  }

  private static int countOccurrences(String value, String needle) {
    int count = 0;
    int index = 0;
    while ((index = value.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }
}
