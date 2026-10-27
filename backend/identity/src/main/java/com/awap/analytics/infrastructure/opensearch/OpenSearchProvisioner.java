package com.awap.analytics.infrastructure.opensearch;

import com.awap.analytics.application.AnalyticsIndexNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Creates the OpenSearch index templates and ISM policy required by the analytics sink. All calls
 * are idempotent PUTs: running provisioning on every startup either creates the resource or
 * overwrites it with the identical desired state, and a 409 means another instance won the race.
 */
@Component
@ConditionalOnProperty(name = "awap.analytics.opensearch.enabled", havingValue = "true")
public class OpenSearchProvisioner {

  private static final Logger log = LoggerFactory.getLogger(OpenSearchProvisioner.class);

  private final OpenSearchProperties properties;
  private final OpenSearchTransport transport;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  public OpenSearchProvisioner(
      OpenSearchProperties properties,
      OpenSearchTransport transport,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.properties = properties;
    this.transport = transport;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void provisionOnStartup() {
    if (!provision()) {
      log.warn(
          "OpenSearch analytics provisioning was incomplete; the sink will retry on the next restart");
    }
  }

  /**
   * Provisions ISM policy and both templates. Returns {@code true} when all resources are ready.
   */
  public boolean provision() {
    AnalyticsIndexNames names = new AnalyticsIndexNames(properties.index(), properties.dlqIndex());
    boolean ism =
        put(
            "/_plugins/_ism/policies/" + properties.ismPolicyId(),
            render(
                "awap-analytics-ism-policy.json",
                Map.of(
                    "{{description}}", properties.ilm().description(),
                    "{{hot_days}}", String.valueOf(properties.ilm().hotDays()),
                    "{{delete_days}}", String.valueOf(properties.ilm().deleteDays()),
                    "{{index_pattern}}", names.indexPattern())),
            "ism_policy");
    boolean template =
        put(
            "/_index_template/" + properties.indexTemplateName(),
            render(
                "awap-analytics-index-template.json",
                Map.of(
                    "{{index_pattern}}", names.indexPattern(),
                    "{{priority}}", "100",
                    "{{ism_policy_id}}", properties.ismPolicyId())),
            "index_template");
    boolean dlqTemplate =
        put(
            "/_index_template/" + properties.dlqIndexTemplateName(),
            render(
                "awap-analytics-dlq-template.json",
                Map.of("{{dlq_pattern}}", names.deadLetterPattern(), "{{priority}}", "200")),
            "dlq_index_template");
    return ism && template && dlqTemplate;
  }

  private boolean put(String path, String body, String component) {
    try {
      OpenSearchResponse response = transport.execute("PUT", path, body);
      if (response.successful() || response.status() == 409) {
        increment(component, "success");
        return true;
      }
      increment(component, "failure");
      log.warn("OpenSearch provisioning call {} failed with status {}", path, response.status());
      return false;
    } catch (IOException ex) {
      increment(component, "failure");
      log.warn("OpenSearch provisioning call {} failed: {}", path, ex.getMessage());
      return false;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      increment(component, "failure");
      return false;
    }
  }

  private String render(String resourceName, Map<String, String> replacements) {
    try {
      String body =
          new ClassPathResource("opensearch/" + resourceName)
              .getContentAsString(StandardCharsets.UTF_8);
      for (Map.Entry<String, String> replacement : replacements.entrySet()) {
        body = body.replace(replacement.getKey(), replacement.getValue());
      }
      return objectMapper.writeValueAsString(objectMapper.readTree(body));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "OpenSearch provisioning file " + resourceName + " is not valid JSON", ex);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Cannot load OpenSearch provisioning file " + resourceName, ex);
    }
  }

  private void increment(String component, String outcome) {
    meterRegistry
        .counter("awap.analytics.sink.provisioning", "component", component, "outcome", outcome)
        .increment();
  }
}
