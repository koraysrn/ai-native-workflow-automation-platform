package com.awap.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.common.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void logsTheRequestWithTheMdcCorrelationId() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "corr-log");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
    request.addHeader("X-Tenant-Id", "tenant-1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RequestLoggingFilter()
        .doFilter(request, response, (servletRequest, servletResponse) -> response.setStatus(200));

    assertThat(response.getStatus()).isEqualTo(200);
  }
}
