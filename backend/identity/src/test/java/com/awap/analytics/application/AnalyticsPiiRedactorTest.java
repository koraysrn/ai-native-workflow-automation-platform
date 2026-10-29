package com.awap.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsPiiRedactorTest {

  private final AnalyticsPiiRedactor redactor = new AnalyticsPiiRedactor();

  @Test
  void keepsOnlyAllowlistedFieldsWithMixedCaseAndSeparators() {
    UUID tenant = UUID.randomUUID();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("TenantID", tenant.toString());
    input.put("EVENT_TYPE", "TASK_COMPLETED");
    input.put("Workflow-Instance-Id", UUID.randomUUID().toString());
    input.put("Task_ID", UUID.randomUUID().toString());
    input.put("DurationMS", 42L);
    input.put("TIMESTAMP", Instant.parse("2026-10-27T10:15:30Z").toString());
    input.put("Email", "secret@example.com");
    input.put("user", Map.of("eMail", "nested@example.com"));
    input.put("tags", List.of("customer@example.com"));

    Map<String, Object> redacted = redactor.redact(input);

    assertThat(redacted)
        .containsOnlyKeys(
            "tenantId", "eventType", "workflowInstanceId", "taskId", "durationMs", "timestamp");
    assertThat(redacted).doesNotContainValue("secret@example.com");
    assertThat(redacted.values().toString()).doesNotContain("example.com");
  }

  @Test
  void masksPiiWhenItAppearsInsideAnAllowlistedValue() {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("tenantId", "user@example.com");
    input.put("eventType", "TASK_COMPLETED");

    Map<String, Object> redacted = redactor.redact(input);

    assertThat(redacted.get("tenantId")).isEqualTo(AnalyticsPiiRedactor.REDACTED);
    assertThat(redacted.get("eventType")).isEqualTo("TASK_COMPLETED");
  }

  @Test
  void masksEmailPhoneAndSsnInsideFreeText() {
    String text = "failed for jane.doe@example.com phone +90 555 123 45 67 ssn 123-45-6789";

    String redacted = redactor.redactText(text);

    assertThat(redacted).doesNotContain("jane.doe@example.com");
    assertThat(redacted).doesNotContain("555 123 45 67");
    assertThat(redacted).doesNotContain("123-45-6789");
    assertThat(redacted).contains(AnalyticsPiiRedactor.REDACTED);
  }

  @Test
  void detectsPiiValues() {
    assertThat(redactor.containsPii("someone@example.com")).isTrue();
    assertThat(redactor.containsPii("+90 (555) 123-4567")).isTrue();
    assertThat(redactor.containsPii("123-45-6789")).isTrue();
    assertThat(redactor.containsPii("TASK_COMPLETED")).isFalse();
  }

  @Test
  void returnsEmptyDocumentForNullInput() {
    assertThat(redactor.redact(null)).isEmpty();
  }
}
