package com.awap.notification.domain;

/** Delivery channels supported by the notification module (ADR-003). */
public enum NotificationChannel {
  EMAIL,
  SMS,
  PUSH,
  WEB,
  WEBHOOK
}
