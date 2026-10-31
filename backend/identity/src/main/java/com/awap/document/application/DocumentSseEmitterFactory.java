package com.awap.document.application;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Factory for {@link SseEmitter} instances; overridable in tests with a recording emitter. */
@Component
public class DocumentSseEmitterFactory {

  public SseEmitter create(long timeoutMillis) {
    return new SseEmitter(timeoutMillis);
  }
}
