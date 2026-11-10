package com.awap.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void keepsAValidHeaderInTheMdcAndResponse() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
    request.addHeader(CorrelationId.HEADER, "client-id-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> duringChain = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) -> duringChain.set(MDC.get(CorrelationId.MDC_KEY)));

    assertThat(duringChain.get()).isEqualTo("client-id-1");
    assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("client-id-1");
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  void generatesAnIdWhenTheHeaderIsMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> duringChain = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) -> duringChain.set(MDC.get(CorrelationId.MDC_KEY)));

    assertThat(duringChain.get()).isNotBlank();
    assertThat(CorrelationId.isValid(duringChain.get())).isTrue();
    assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(duringChain.get());
  }

  @Test
  void replacesAnUnsafeHeaderAndClearsTheMdcEvenWhenTheChainFails() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
    request.addHeader(CorrelationId.HEADER, "bad\nvalue");
    MockHttpServletResponse response = new MockHttpServletResponse();

    try {
      filter.doFilter(
          request,
          response,
          (servletRequest, servletResponse) -> {
            throw new IllegalStateException("boom");
          });
    } catch (Exception ignored) {
      // The MDC contract is what this test asserts.
    }

    String echoed = response.getHeader(CorrelationId.HEADER);
    assertThat(echoed).isNotEqualTo("bad\nvalue");
    assertThat(CorrelationId.isValid(echoed)).isTrue();
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }
}
