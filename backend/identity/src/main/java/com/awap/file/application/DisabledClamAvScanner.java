package com.awap.file.application;

import com.awap.file.domain.FileScanStatus;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test fallback: when ClamAV is not enabled, files are marked CLEAN. Never loaded in the
 * production profile, so production always requires the real {@link ClamdClamAvScanner}.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "awap.clamav.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledClamAvScanner implements ClamAvScanner {

  @Override
  public FileScanStatus scan(InputStream content) {
    return FileScanStatus.CLEAN;
  }
}
