package com.awap.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the SpEL sandbox: forbidden patterns, parsing and read-only evaluation. */
class SpelExpressionServiceTest {

  private final SpelExpressionService service = new SpelExpressionService();

  @Test
  void rejectsNullAndBlankQuietly() {
    service.validate(null);
    service.validate("   ");
  }

  @Test
  void rejectsForbiddenPatterns() {
    for (String expression :
        new String[] {
          "T(java.lang.Runtime).getRuntime().exec('id')",
          "new java.lang.ProcessBuilder('sh')",
          "T(java.lang.System)",
          "'x'.getClass()",
          "T(ProcessBuilder)"
        }) {
      assertThatThrownBy(() -> service.validate(expression)).isInstanceOf(AwapException.class);
    }
  }

  @Test
  void rejectsInvalidExpression() {
    assertThatThrownBy(() -> service.validate("1 +")).isInstanceOf(AwapException.class);
  }

  @Test
  void evaluatesVariableComparison() {
    Object result = service.evaluate("#amount > 100", Map.of("amount", 250));
    assertThat(result).isEqualTo(Boolean.TRUE);
  }

  @Test
  void evaluatesAgainstRootObject() {
    record Payload(int amount) {}
    Object result = service.evaluate("amount * 2", new Payload(21));
    assertThat(result).isEqualTo(42);
  }

  @Test
  void evaluatesArithmeticAcrossVariables() {
    Object result = service.evaluate("#a + #b", Map.of("a", 2, "b", 3));
    assertThat(result).isEqualTo(5);
  }
}
