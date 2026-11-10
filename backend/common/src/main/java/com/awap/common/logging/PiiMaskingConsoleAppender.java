package com.awap.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Console appender that masks the formatted message and every MDC value before the JSON encoder
 * sees the event (issue #191).
 *
 * <p>Logback has no built-in message interceptor, so the event is wrapped in a dynamic proxy that
 * overrides only the message/MDC accessors and delegates everything else to the original event. The
 * configuration therefore stays a plain {@code ConsoleAppender} plus the JSON encoder.
 */
public class PiiMaskingConsoleAppender extends ConsoleAppender<ILoggingEvent> {

  @Override
  public void append(ILoggingEvent event) {
    super.append(masked(event));
  }

  static ILoggingEvent masked(ILoggingEvent event) {
    return (ILoggingEvent)
        Proxy.newProxyInstance(
            ILoggingEvent.class.getClassLoader(),
            new Class<?>[] {ILoggingEvent.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getFormattedMessage":
                  return PiiMasker.mask(event.getFormattedMessage());
                case "getMessage":
                  return PiiMasker.mask(event.getMessage());
                case "getMDCPropertyMap":
                  return maskedMdc(event.getMDCPropertyMap());
                default:
                  return method.invoke(event, args);
              }
            });
  }

  private static Map<String, String> maskedMdc(Map<String, String> source) {
    if (source == null || source.isEmpty()) {
      return source;
    }
    Map<String, String> masked = new LinkedHashMap<>();
    source.forEach((key, value) -> masked.put(key, PiiMasker.mask(value)));
    return masked;
  }
}
