package com.awap.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class PiiMaskingConsoleAppenderTest {

  @Test
  void masksTheMessageAndMdcBeforeTheJsonEncoderSeesTheEvent() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger logger = context.getLogger("pii-masking-test");
    MDC.put("correlationId", "corr-1");
    MDC.put("email", "bob@example.com");
    try {
      LoggingEvent event =
          new LoggingEvent(
              "pii-masking-test",
              logger,
              Level.INFO,
              "login for {} with token {}",
              null,
              new Object[] {"alice@example.com", "A".repeat(48)});

      ILoggingEvent masked = PiiMaskingConsoleAppender.masked(event);

      assertThat(masked.getMessage()).isNotNull();
      assertThat(masked.getLoggerName()).isEqualTo("pii-masking-test");
      assertThat(masked.getFormattedMessage()).contains("a***@example.com");
      assertThat(masked.getFormattedMessage()).doesNotContain("alice@example.com");
      assertThat(masked.getFormattedMessage()).contains("sha256:");
      assertThat(masked.getMDCPropertyMap()).containsEntry("email", "b***@example.com");
      assertThat(masked.getMDCPropertyMap()).containsEntry("correlationId", "corr-1");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void handlesAnEmptyMdcMap() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger logger = context.getLogger("pii-masking-empty");
    LoggingEvent event =
        new LoggingEvent("pii-masking-empty", logger, Level.INFO, "no mdc", null, null);

    ILoggingEvent masked = PiiMaskingConsoleAppender.masked(event);

    assertThat(masked.getMDCPropertyMap()).isNullOrEmpty();
  }
}
