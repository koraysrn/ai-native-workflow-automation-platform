package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** SMS adapter. Thin transport seam for a future SMS provider SDK. */
@Component
public class SmsChannelAdapter implements NotificationChannelAdapter {

  private static final Logger log = LoggerFactory.getLogger(SmsChannelAdapter.class);

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.SMS;
  }

  @Override
  public void dispatch(NotificationMessage message) {
    log.info("SMS -> {} bytes={}", message.recipient(), message.body().length());
  }
}
