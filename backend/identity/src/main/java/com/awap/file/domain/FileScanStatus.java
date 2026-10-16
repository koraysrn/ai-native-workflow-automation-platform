package com.awap.file.domain;

/** Antivirus scan lifecycle of an uploaded file. */
public enum FileScanStatus {
  PENDING,
  CLEAN,
  INFECTED
}
