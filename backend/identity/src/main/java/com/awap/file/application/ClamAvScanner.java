package com.awap.file.application;

import com.awap.file.domain.FileScanStatus;

/** Scans an object for malware. Implementations plug in a real ClamAV daemon. */
public interface ClamAvScanner {

  FileScanStatus scan(String bucket, String objectKey);
}
