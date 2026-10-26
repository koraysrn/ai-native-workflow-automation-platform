package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-app/web notification adapter. The durable delivery row created by {@link NotificationService}
 * is the in-app inbox entry; this adapter only records the hand-off so the same delivery pipeline
 * covers web notifications as well.
 */
@Component
public class WebChannelAdapter implements NotificationChannelAdapter {

  private static final Logger log = LoggerFactory.getLogger(WebChannelAdapter.class);

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.WEB;
  }

  @Override
  public void dispatch(NotificationMessage message) {
    log.info("WEB -> {} bytes={}", message.recipient(), message.body().length());
  }
}
