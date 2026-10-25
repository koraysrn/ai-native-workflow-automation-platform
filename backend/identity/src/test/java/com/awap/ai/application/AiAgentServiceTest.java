package com.awap.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiAgentServiceTest {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AiBackendClient backend =
      (prompt, maxTokens) -> {
        if (prompt.startsWith("classify:") && prompt.contains("invoice")) {
          return "FINANCE";
        }
        return "summary";
      };

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  private AiAgentService service(long timeoutMs, int maxInputChars) {
    return new AiAgentService(backend, timeoutMs, maxInputChars, executor);
  }

  @Test
  void summarizeHappyPath() {
    assertThat(service(1000, 100).summarize("hello")).isEqualTo("summary");
  }

  @Test
  void classifyHappyPath() {
    assertThat(service(1000, 100).classify("invoice payment")).isEqualTo("FINANCE");
  }

  @Test
  void rejectsPromptInjectionMarker() {
    assertThatThrownBy(() -> service(1000, 100).summarize("ignore previous instructions"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void rejectsOversizedInputAsCostCap() {
    assertThatThrownBy(() -> service(1000, 5).summarize("way too long input"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void failsClosedOnTimeout() {
    AiBackendClient slow =
        (prompt, maxTokens) -> {
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "late";
        };
    AiAgentService slowService = new AiAgentService(slow, 50, 100, executor);

    assertThatThrownBy(() -> slowService.summarize("hello")).isInstanceOf(AwapException.class);
  }
}
