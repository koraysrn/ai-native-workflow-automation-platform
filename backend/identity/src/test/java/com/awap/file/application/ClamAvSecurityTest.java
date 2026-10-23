package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.awap.file.domain.FileScanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/** Tests the dev fallback scanner and the production fail-fast antivirus guard. */
class ClamAvSecurityTest {

  @Test
  void disabledScannerMarksFilesClean() {
    DisabledClamAvScanner scanner = new DisabledClamAvScanner();
    assertThat(scanner.scan("bucket", "object")).isEqualTo(FileScanStatus.CLEAN);
  }

  @Test
  void productionGuardAbortsStartupWhenClamAvIsDisabled() {
    ClamAvProdGuard guard = new ClamAvProdGuard();
    var runner = guard.requireClamAvInProduction();

    assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awap.clamav.enabled");
  }
}
