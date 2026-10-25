package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Push adapter. Thin transport seam for a future FCM/APNs provider SDK. */
@Component
public class PushChannelAdapter implements NotificationChannelAdapter {

  private static final Logger log = LoggerFactory.getLogger(PushChannelAdapter.class);

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.PUSH;
  }

  @Override
  public void dispatch(NotificationMessage message) {
    log.info("PUSH -> {} bytes={}", message.recipient(), message.body().length());
  }
}
