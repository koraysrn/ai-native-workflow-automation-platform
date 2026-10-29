package com.awap.script.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.script.domain.ScriptSandboxException;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ScriptSandboxTest {

  private final ScriptSandbox sandbox =
      new ScriptSandbox(1_000, 100, Executors.newVirtualThreadPerTaskExecutor(), 0);

  @Test
  void evaluatesArithmeticWithPrecedence() {
    assertThat(sandbox.evaluate("1 + 2 * 3", Map.of()).value()).isEqualTo(7L);
    assertThat(sandbox.evaluate("(1 + 2) * 3", Map.of()).value()).isEqualTo(9L);
    assertThat(sandbox.evaluate("10 / 4", Map.of()).value()).isEqualTo(2.5);
    assertThat(sandbox.evaluate("-2 + 5", Map.of()).value()).isEqualTo(3L);
  }

  @Test
  void exposesOnlyTheTypedContextMap() {
    Map<String, Object> context = Map.of("total", 21, "name", "ada", "age", 17);

    assertThat(sandbox.evaluate("ctx.total * 2", context).value()).isEqualTo(42L);
    assertThat(sandbox.evaluate("upper(ctx.name)", context).value()).isEqualTo("ADA");
    assertThat(sandbox.evaluate("len(ctx.name)", context).value()).isEqualTo(3);
    assertThat(sandbox.evaluate("coalesce(ctx.missing, 'fallback')", context).value())
        .isEqualTo("fallback");
    assertThat(sandbox.evaluate("ctx.missing", context).value()).isNull();
  }

  @Test
  void supportsComparisonsAndBooleanLogic() {
    Map<String, Object> context = Map.of("age", 21);

    assertThat(sandbox.evaluate("ctx.age >= 18 and not (ctx.age < 0)", context).value())
        .isEqualTo(true);
    assertThat(sandbox.evaluate("'a' + 1", context).value()).isEqualTo("a1");
    assertThat(sandbox.evaluate("1 == 1", context).value()).isEqualTo(true);
    assertThat(sandbox.evaluate("1 != 2", context).value()).isEqualTo(true);
  }

  @Test
  void rejectsHostAccessAttempts() {
    for (String script :
        new String[] {
          "Runtime.getRuntime()",
          "System.exit(0)",
          "java.lang.System",
          "env.SECRET",
          "new java.io.File('/etc/passwd')",
          "ctx.name.getClass()",
          "exec('rm -rf /')",
          "import java.io"
        }) {
      assertThatThrownBy(() -> sandbox.evaluate(script, Map.of("name", "ada")))
          .as(script)
          .isInstanceOf(ScriptSandboxException.class);
    }
  }

  @Test
  void rejectsMalformedScriptsAndDivisionByZero() {
    assertThatThrownBy(() -> sandbox.evaluate("", Map.of()))
        .isInstanceOf(ScriptSandboxException.class);
    assertThatThrownBy(() -> sandbox.evaluate("'unterminated", Map.of()))
        .isInstanceOf(ScriptSandboxException.class);
    assertThatThrownBy(() -> sandbox.evaluate("1 / 0", Map.of()))
        .isInstanceOf(ScriptSandboxException.class)
        .hasMessageContaining("Division by zero");
    assertThatThrownBy(
            () -> sandbox.evaluate("a".repeat(ScriptSandbox.MAX_SCRIPT_CHARS + 1), Map.of()))
        .isInstanceOf(ScriptSandboxException.class)
        .hasMessageContaining("exceeds");
  }

  @Test
  void enforcesTheOutputCap() {
    ScriptSandbox tinyOutput =
        new ScriptSandbox(1_000, 3, Executors.newVirtualThreadPerTaskExecutor(), 0);

    assertThatThrownBy(() -> tinyOutput.evaluate("'abcdefgh'", Map.of()))
        .isInstanceOf(ScriptSandboxException.class)
        .hasMessageContaining("output exceeds");
  }

  @Test
  void timesOutAndFlagsTheFailure() {
    ScriptSandbox slow =
        new ScriptSandbox(5, 100, Executors.newVirtualThreadPerTaskExecutor(), 250);

    assertThatThrownBy(() -> slow.evaluate("1 + 1", Map.of()))
        .isInstanceOfSatisfying(
            ScriptSandboxException.class,
            ex -> {
              assertThat(ex.isTimeout()).isTrue();
              assertThat(ex.getMessage()).contains("timed out");
            });
  }

  @Test
  void neverSeesEnvironmentVariables() {
    assertThatThrownBy(() -> sandbox.evaluate("PATH", Map.of()))
        .isInstanceOf(ScriptSandboxException.class)
        .hasMessageContaining("not allowed");
  }
}
