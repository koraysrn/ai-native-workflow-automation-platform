package com.awap.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.workflow.domain.NodeRetryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Generic node retry policy: parsing, delay sequence, classification and validation. */
class NodeRetryPolicyTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private JsonNode json(String value) throws IOException {
    return objectMapper.readTree(value);
  }

  @Test
  void defaultsMatchTheLegacyBudget() {
    NodeRetryPolicy policy = NodeRetryPolicy.defaults();

    assertThat(policy.maxAttempts()).isEqualTo(3);
    assertThat(policy.delaysMs()).containsExactly(1_000L);
    assertThat(policy.jitterRatio()).isZero();
    assertThat(policy.retryableErrorClasses()).isEmpty();
    assertThat(policy.allowsAnotherAttempt(2)).isTrue();
    assertThat(policy.isExhausted(3)).isTrue();
  }

  @Test
  void parsesTheFullPolicy() throws IOException {
    NodeRetryPolicy policy =
        NodeRetryPolicy.fromConfig(
            json(
                "{\"maxAttempts\":4,\"delaysMs\":[1000,5000,25000],\"jitterRatio\":0.2,"
                    + "\"retryableErrorClasses\":[\"TimeoutException\",\"IOException\"]}"));

    assertThat(policy.maxAttempts()).isEqualTo(4);
    assertThat(policy.delaysMs()).containsExactly(1_000L, 5_000L, 25_000L);
    assertThat(policy.jitterRatio()).isEqualTo(0.2);
    assertThat(policy.retryableErrorClasses())
        .containsExactlyInAnyOrder("TimeoutException", "IOException");
    assertThat(policy.describe()).contains("maxAttempts=4").contains("jitterRatio=0.2");
  }

  @Test
  void usesTheConfiguredDelaySequenceAndReusesTheLastEntry() throws IOException {
    NodeRetryPolicy policy =
        NodeRetryPolicy.fromConfig(json("{\"maxAttempts\":5,\"delaysMs\":[1000,5000,25000]}"));

    assertThat(policy.baseDelayMs(1)).isEqualTo(1_000L);
    assertThat(policy.baseDelayMs(2)).isEqualTo(5_000L);
    assertThat(policy.baseDelayMs(3)).isEqualTo(25_000L);
    assertThat(policy.baseDelayMs(4)).isEqualTo(25_000L);
  }

  @Test
  void aSingleBackoffValueBuildsAOneEntrySequence() throws IOException {
    NodeRetryPolicy policy =
        NodeRetryPolicy.fromConfig(json("{\"maxAttempts\":2,\"backoffMs\":2500}"));

    assertThat(policy.delaysMs()).containsExactly(2_500L);
  }

  @Test
  void jitterIsBoundedAndNeverProducesASubMillisecondDelay() throws IOException {
    NodeRetryPolicy policy =
        NodeRetryPolicy.fromConfig(
            json("{\"maxAttempts\":3,\"delaysMs\":[1000],\"jitterRatio\":0.5}"));
    Instant now = Instant.parse("2026-01-01T10:00:00Z");

    assertThat(policy.nextAttemptAt(now, 1, 0.0)).isEqualTo(now.plusMillis(500));
    assertThat(policy.nextAttemptAt(now, 1, 0.5)).isEqualTo(now.plusMillis(1_000));
    assertThat(policy.nextAttemptAt(now, 1, 1.0)).isEqualTo(now.plusMillis(1_500));
    assertThat(Duration.between(now, policy.nextAttemptAt(now, 1, 0.0))).isPositive();
  }

  @Test
  void permanentSafetyErrorsAreNeverRetryable() {
    NodeRetryPolicy policy = NodeRetryPolicy.defaults();

    assertThat(
            policy.isRetryable(
                new AwapException(
                    ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "bad definition")))
        .isFalse();
    assertThat(
            policy.isRetryable(
                new AwapException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "denied")))
        .isFalse();
    assertThat(
            policy.isRetryable(
                new AwapException(
                    ErrorCode.GENERIC_INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "boom")))
        .isTrue();
    assertThat(policy.isRetryable(new IllegalStateException("transient"))).isTrue();
  }

  @Test
  void aConfiguredClassListGatesTheClassification() throws IOException {
    NodeRetryPolicy policy =
        NodeRetryPolicy.fromConfig(
            json("{\"maxAttempts\":3,\"retryableErrorClasses\":[\"TimeoutException\"]}"));

    assertThat(policy.isRetryable(new RuntimeException(new TimeoutException("slow")))).isTrue();
    assertThat(policy.isRetryable(new IllegalStateException("unlisted"))).isFalse();
  }

  @Test
  void aConfiguredListCanMatchAnErrorCodeName() throws IOException {
    NodeRetryPolicy policy =
        NodeRetryPolicy.fromConfig(
            json("{\"maxAttempts\":3,\"retryableErrorClasses\":[\"GENERIC_INTERNAL_ERROR\"]}"));

    assertThat(
            policy.isRetryable(
                new AwapException(
                    ErrorCode.GENERIC_INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "boom")))
        .isTrue();
    assertThat(
            policy.isRetryable(
                new AwapException(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "missing")))
        .isFalse();
  }

  @Test
  void validatesEveryFieldAtDefinitionSaveTime() {
    assertInvalid("[]", "must be a JSON object");
    assertInvalid("{\"maxAttempts\":0}", "maxAttempts");
    assertInvalid("{\"maxAttempts\":21}", "maxAttempts");
    assertInvalid("{\"delaysMs\":[]}", "delaysMs");
    assertInvalid("{\"delaysMs\":[0]}", "delaysMs");
    assertInvalid(
        "{\"delaysMs\":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21]}", "delaysMs");
    assertInvalid("{\"delaysMs\":[\"soon\"]}", "integers");
    assertInvalid("{\"backoffMs\":0}", "backoffMs");
    assertInvalid("{\"jitterRatio\":1.5}", "jitterRatio");
    assertInvalid("{\"retryableErrorClasses\":\"TimeoutException\"}", "array");
    assertInvalid("{\"retryableErrorClasses\":[\" \"]}", "blank");
  }

  private void assertInvalid(String json, String messagePart) {
    assertThatThrownBy(() -> NodeRetryPolicy.fromConfig(json(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(messagePart);
  }

  @Test
  void legacyValuesAreClampedToSafeBounds() {
    assertThat(NodeRetryPolicy.legacy(0, 0).maxAttempts()).isEqualTo(1);
    assertThat(NodeRetryPolicy.legacy(99, 0).maxAttempts()).isEqualTo(20);
    assertThat(NodeRetryPolicy.legacy(3, -5).delaysMs()).containsExactly(1L);
  }
}
