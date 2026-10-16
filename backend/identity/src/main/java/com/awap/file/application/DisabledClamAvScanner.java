package com.awap.file.application;

import com.awap.file.domain.FileScanStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dev fallback: when ClamAV is not enabled, files are marked CLEAN. */
@Component
@ConditionalOnProperty(name = "awap.clamav.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledClamAvScanner implements ClamAvScanner {

  @Override
  public FileScanStatus scan(String bucket, String objectKey) {
    return FileScanStatus.CLEAN;
  }
}
