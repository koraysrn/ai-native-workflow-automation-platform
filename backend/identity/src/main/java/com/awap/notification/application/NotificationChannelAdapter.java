package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;

/** Thin per-channel dispatch adapter. Throws on provider failure so the caller can retry. */
public interface NotificationChannelAdapter {

  NotificationChannel channel();

  void dispatch(NotificationMessage message);
}
