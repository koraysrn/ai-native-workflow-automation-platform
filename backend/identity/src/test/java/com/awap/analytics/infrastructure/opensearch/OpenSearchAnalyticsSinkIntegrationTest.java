package com.awap.analytics.infrastructure.opensearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real OpenSearch integration coverage for the analytics sink. Docker is required; when it is not
 * available the entire class is skipped via {@code disabledWithoutDocker = true}.
 */
@Testcontainers(disabledWithoutDocker = true)
class OpenSearchAnalyticsSinkIntegrationTest {

  private static final String PREFIX = "awap-analytics-itest";
  private static final String DLQ_PREFIX = "awap-analytics-itest-dlq";
  private static final Instant TIMESTAMP = Instant.parse("2026-10-27T10:15:30Z");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Container
  static final GenericContainer<?> OPENSEARCH =
      new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2.19.0"))
          .withExposedPorts(9200)
          .withEnv("discovery.type", "single-node")
          .withEnv("DISABLE_SECURITY_PLUGIN", "true")
          .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
          .withEnv("action.destructive_requires_name", "false")
          .withEnv("bootstrap.memory_lock", "false")
          .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
          .waitingFor(
              Wait.forHttp("/_cluster/health")
                  .forPort(9200)
                  .withStartupTimeout(Duration.ofMinutes(5)));

  private JdkOpenSearchTransport transport;
  private OpenSearchProperties properties;
  private OpenSearchProvisioner provisioner;
  private SimpleMeterRegistry meters;
  private OpenSearchAnalyticsEventSink sink;
  private ScheduledExecutorService executor;

  @BeforeEach
  void setUp() throws Exception {
    String endpoint = "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
    properties =
        new OpenSearchProperties(
            true,
            endpoint,
            PREFIX,
            DLQ_PREFIX,
            100,
            100,
            3_600_000L,
            List.of(20L, 20L, 20L),
            4,
            10,
            1_000L,
            1_000L,
            2_000L,
            "awap-analytics-ism-itest",
            "awap-analytics-template-itest",
            "awap-analytics-dlq-template-itest",
            new OpenSearchProperties.Ilm(7, 30, 180));
    transport = new JdkOpenSearchTransport(properties);
    meters = new SimpleMeterRegistry();
    provisioner = new OpenSearchProvisioner(properties, transport, MAPPER, meters);
    transport.execute("DELETE", "/" + PREFIX + "-*", null);
    assertThat(provisioner.provision()).isTrue();
    sink = createSink(transport);
  }

  @AfterEach
  void tearDown() {
    if (sink != null) {
      sink.close();
    }
  }

  private OpenSearchAnalyticsEventSink createSink(OpenSearchTransport sinkTransport) {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "opensearch-itest-sink");
              thread.setDaemon(true);
              return thread;
            });
    return new OpenSearchAnalyticsEventSink(
        properties, MAPPER, meters, sinkTransport, System::nanoTime, executor);
  }

  @Test
  void indexesValidEventWithExplicitMappingAndDailyIndexName() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID task = UUID.randomUUID();

    sink.write(OpenSearchTestSupport.event(tenant, "TASK_COMPLETED", task, TIMESTAMP, 42L));
    sink.flushNow();

    String index = PREFIX + "-2026.10.27";
    refresh(index);
    assertThat(count(index)).isEqualTo(1);

    JsonNode mapping = json(get("/" + index + "/_mapping"));
    JsonNode propertiesNode = mapping.path(index).path("mappings").path("properties");
    assertThat(propertiesNode.path("tenantId").path("type").asText()).isEqualTo("keyword");
    assertThat(propertiesNode.path("eventType").path("type").asText()).isEqualTo("keyword");
    assertThat(propertiesNode.path("workflowInstanceId").path("type").asText())
        .isEqualTo("keyword");
    assertThat(propertiesNode.path("taskId").path("type").asText()).isEqualTo("keyword");
    assertThat(propertiesNode.path("durationMs").path("type").asText()).isEqualTo("long");
    assertThat(propertiesNode.path("timestamp").path("type").asText()).isEqualTo("date");

    JsonNode template =
        json(get("/_index_template/awap-analytics-template-itest"))
            .path("index_templates")
            .get(0)
            .path("index_template");
    assertThat(template.path("index_patterns").get(0).asText()).isEqualTo(PREFIX + "-*");
    assertThat(template.toString()).contains("awap-analytics-ism-itest");

    JsonNode search =
        json(
            post(
                "/" + index + "/_search",
                "{\"query\":{\"term\":{\"tenantId\":\"" + tenant + "\"}}}"));
    assertThat(totalHits(search)).isEqualTo(1);
    assertThat(search.path("hits").path("hits").get(0).path("_source").path("taskId").asText())
        .isEqualTo(task.toString());
    assertThat(counter("awap.analytics.sink.events", "outcome", "success")).isEqualTo(1d);
  }

  @Test
  void bulkIndexesEveryDocumentInTheBatch() throws Exception {
    for (int i = 0; i < 5; i++) {
      sink.write(
          OpenSearchTestSupport.event(
              UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, i + 1L));
    }

    sink.flushNow();

    String index = PREFIX + "-2026.10.27";
    refresh(index);
    assertThat(count(index)).isEqualTo(5);
    assertThat(counter("awap.analytics.sink.events", "outcome", "success")).isEqualTo(5d);
  }

  @Test
  void piiIsRedactedBeforeIndexing() throws Exception {
    UUID tenant = UUID.randomUUID();
    Map<String, Object> document =
        OpenSearchTestSupport.event(tenant, "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L);
    document.put("Email", "secret@example.com");
    document.put("user", Map.of("email", "nested@example.com", "phone", "+90 555 123 45 67"));

    sink.write(document);
    sink.flushNow();

    String index = PREFIX + "-2026.10.27";
    refresh(index);
    JsonNode search =
        json(
            post(
                "/" + index + "/_search",
                "{\"query\":{\"term\":{\"tenantId\":\"" + tenant + "\"}}}"));
    JsonNode source = search.path("hits").path("hits").get(0).path("_source");
    assertThat(source.toString()).doesNotContain("example.com");
    assertThat(source.toString()).doesNotContain("555 123 45 67");
    assertThat(source.has("Email")).isFalse();
    assertThat(source.has("user")).isFalse();
  }

  @Test
  void mappingConflictGoesToDeadLetterAndLaterEventsStillIndex() throws Exception {
    Map<String, Object> invalid =
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L);
    invalid.put("durationMs", "not-a-number");

    sink.write(invalid);
    sink.flushNow();

    String mainIndex = PREFIX + "-2026.10.27";
    String deadLetterIndex = DLQ_PREFIX + "-" + datePart();
    refresh(deadLetterIndex);
    assertThat(count(mainIndex)).isZero();
    assertThat(count(deadLetterIndex)).isEqualTo(1);
    JsonNode deadLetter = json(get("/" + deadLetterIndex + "/_search"));
    assertThat(deadLetter.path("hits").path("hits").get(0).path("_source").path("reason").asText())
        .isEqualTo("mapping_conflict");

    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 2L));
    sink.flushNow();
    refresh(mainIndex);
    assertThat(count(mainIndex)).isEqualTo(1);
  }

  @Test
  void provisioningIsIdempotentAndAttachesRetentionPolicy() throws Exception {
    assertThat(provisioner.provision()).isTrue();

    String policy = get("/_plugins/_ism/policies/awap-analytics-ism-itest").body();
    assertThat(policy)
        .contains("\"min_index_age\":\"7d\"")
        .contains("\"min_index_age\":\"180d\"")
        .contains("hot 7d, warm 30d, delete 180d");

    String template = get("/_index_template/awap-analytics-template-itest").body();
    assertThat(template)
        .contains("\"tenantId\":{\"type\":\"keyword\"}")
        .contains("\"durationMs\":{\"type\":\"long\"}")
        .contains("awap-analytics-ism-itest");
  }

  @Test
  void temporaryOutageRetriesAndEventuallyIndexes() throws Exception {
    sink.close();
    sink = createSink(new FlakyTransport(transport));

    sink.write(
        OpenSearchTestSupport.event(
            UUID.randomUUID(), "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L));

    String index = PREFIX + "-2026.10.27";
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      sink.flushNow();
      refresh(index);
      if (count(index) == 1) {
        break;
      }
      Thread.sleep(20);
    }

    refresh(index);
    assertThat(count(index)).isEqualTo(1);
    assertThat(counter("awap.analytics.sink.events", "outcome", "success")).isEqualTo(1d);
    assertThat(counter("awap.analytics.sink.retries", "reason", "timeout"))
        .isGreaterThanOrEqualTo(1d);
    assertThat(counter("awap.analytics.sink.retries", "reason", "http_503"))
        .isGreaterThanOrEqualTo(1d);
  }

  @Test
  void deletedTenantEventsFollowRetentionPolicy() throws Exception {
    UUID tenant = UUID.randomUUID();
    sink.write(
        OpenSearchTestSupport.event(tenant, "TASK_COMPLETED", UUID.randomUUID(), TIMESTAMP, 1L));
    sink.flushNow();

    String index = PREFIX + "-2026.10.27";
    refresh(index);
    assertThat(count(index)).isEqualTo(1);

    String settings = get("/" + index + "/_settings").body();
    assertThat(settings).contains("awap-analytics-ism-itest");
    OpenSearchTestSupport.await(() -> ismExplainContains(index), 15_000);
  }

  private boolean ismExplainContains(String index) {
    try {
      return get("/_plugins/_ism/explain/" + index).body().contains("awap-analytics-ism-itest");
    } catch (Exception ex) {
      return false;
    }
  }

  private static String datePart() {
    return DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC).format(Instant.now());
  }

  private OpenSearchResponse get(String path) throws Exception {
    return transport.execute("GET", path, null);
  }

  private OpenSearchResponse post(String path, String body) throws Exception {
    return transport.execute("POST", path, body);
  }

  private void refresh(String index) throws Exception {
    transport.execute("POST", "/" + index + "/_refresh", null);
  }

  private int count(String index) throws Exception {
    OpenSearchResponse response = transport.execute("GET", "/" + index + "/_count", null);
    if (!response.successful()) {
      return 0;
    }
    return json(response).path("count").asInt();
  }

  private JsonNode json(OpenSearchResponse response) throws IOException {
    return MAPPER.readTree(response.body());
  }

  private int totalHits(JsonNode search) {
    JsonNode total = search.path("hits").path("total");
    return total.isObject() ? total.path("value").asInt() : total.asInt();
  }

  private double counter(String name, String... tags) {
    Counter counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0d : counter.count();
  }

  /** First sink attempt times out, second returns 503, then the real cluster answers. */
  static final class FlakyTransport implements OpenSearchTransport {

    private final OpenSearchTransport delegate;
    private final AtomicInteger calls = new AtomicInteger();

    FlakyTransport(OpenSearchTransport delegate) {
      this.delegate = delegate;
    }

    @Override
    public OpenSearchResponse execute(String method, String path, String body)
        throws IOException, InterruptedException {
      int call = calls.incrementAndGet();
      if (call == 1) {
        throw new SocketTimeoutException("simulated outage");
      }
      if (call == 2) {
        return new OpenSearchResponse(503, "{}");
      }
      return delegate.execute(method, path, body);
    }
  }
}
