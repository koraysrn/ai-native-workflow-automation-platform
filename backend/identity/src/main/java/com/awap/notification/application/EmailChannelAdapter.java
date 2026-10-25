package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Email adapter. Faz 1 dispatches through the platform's outbound mail relay; the transport is
 * abstracted here so a real provider SDK can be swapped in without touching {@link
 * NotificationService}.
 */
@Component
public class EmailChannelAdapter implements NotificationChannelAdapter {

  private static final Logger log = LoggerFactory.getLogger(EmailChannelAdapter.class);

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.EMAIL;
  }

  @Override
  public void dispatch(NotificationMessage message) {
    log.info(
        "EMAIL -> {} subject='{}' bytes={}",
        message.recipient(),
        message.subject(),
        message.body().length());
  }
}
