package com.awap.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void acceptsASafeClientValue() {
    assertThat(CorrelationId.resolve("req-123.abc")).isEqualTo("req-123.abc");
    assertThat(CorrelationId.resolve("  req-456  ")).isEqualTo("req-456");
  }

  @Test
  void generatesForMissingUnsafeOrOversizedValues() {
    String generated = CorrelationId.resolve(null);
    assertThat(generated).isNotBlank();
    assertThat(CorrelationId.isValid(generated)).isTrue();

    assertThat(CorrelationId.resolve("has space")).isNotEqualTo("has space");
    assertThat(CorrelationId.resolve("line\nbreak")).isNotEqualTo("line\nbreak");
    assertThat(CorrelationId.resolve("x".repeat(65))).hasSizeLessThanOrEqualTo(64);
    assertThat(CorrelationId.resolve("-leading-dash")).isNotEqualTo("-leading-dash");
  }

  @Test
  void currentOrDefaultFallsBackToAGeneratedIdAndCanBeScoped() {
    assertThat(CorrelationId.current()).isEmpty();
    String generated = CorrelationId.currentOrDefault();
    assertThat(CorrelationId.isValid(generated)).isTrue();

    CorrelationId.set("scoped-id");
    assertThat(CorrelationId.current()).contains("scoped-id");
    assertThat(CorrelationId.currentOrDefault()).isEqualTo("scoped-id");

    CorrelationId.clear();
    assertThat(CorrelationId.current()).isEmpty();
  }
}
