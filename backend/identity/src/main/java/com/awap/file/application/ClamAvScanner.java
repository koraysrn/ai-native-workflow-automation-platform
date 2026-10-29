package com.awap.file.application;

import com.awap.file.domain.FileScanStatus;
import java.io.InputStream;

/** Scans a file content stream for malware. Implementations plug in a real ClamAV daemon. */
public interface ClamAvScanner {

  /**
   * Scans the supplied content stream. The caller owns the stream and closes it afterwards.
   *
   * @return {@link FileScanStatus#CLEAN} or {@link FileScanStatus#INFECTED}
   * @throws com.awap.common.api.AwapException when clamd is unreachable, times out or answers with
   *     an unexpected protocol response
   */
  FileScanStatus scan(InputStream content);
}
