package com.awap.notification.domain;

/** Raised when a notification template or its variables violate the template rules. */
public class InvalidNotificationTemplateException extends RuntimeException {

  public InvalidNotificationTemplateException(String message) {
    super(message);
  }
}
