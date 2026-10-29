package com.awap.file.domain;

/** Raised when the object store cannot fulfil a read, copy, delete or presign operation. */
public class FileStorageException extends RuntimeException {

  public FileStorageException(String message) {
    super(message);
  }

  public FileStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
