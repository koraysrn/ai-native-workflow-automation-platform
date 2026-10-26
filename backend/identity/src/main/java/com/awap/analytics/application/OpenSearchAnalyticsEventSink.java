package com.awap.analytics.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Best-effort raw event sink for OpenSearch. Aggregations remain in PostgreSQL, so an OpenSearch
 * outage never blocks workflow execution or the dashboard.
 */
@Component
@ConditionalOnProperty(name = "awap.analytics.opensearch.enabled", havingValue = "true")
public class OpenSearchAnalyticsEventSink implements AnalyticsEventSink {

  private static final Logger log = LoggerFactory.getLogger(OpenSearchAnalyticsEventSink.class);

  private final ObjectMapper objectMapper;
  private final String endpoint;
  private final String index;
  private final HttpClient httpClient;

  public OpenSearchAnalyticsEventSink(
      ObjectMapper objectMapper,
      @Value("${awap.analytics.opensearch.endpoint:http://localhost:9200}") String endpoint,
      @Value("${awap.analytics.opensearch.index:awap-analytics}") String index) {
    this.objectMapper = objectMapper;
    this.endpoint = endpoint;
    this.index = index;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @Override
  public void write(Map<String, Object> document) {
    try {
      String body = objectMapper.writeValueAsString(document);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(endpoint + "/" + index + "/_doc"))
              .timeout(Duration.ofSeconds(3))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      httpClient
          .sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .exceptionally(
              ex -> {
                log.warn("OpenSearch analytics write failed: {}", ex.getMessage());
                return null;
              });
    } catch (Exception ex) {
      log.warn("OpenSearch analytics payload could not be serialized: {}", ex.getMessage());
    }
  }
}
