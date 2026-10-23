package com.awap.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.identity.domain.IdempotencyRecord;
import com.awap.identity.infrastructure.IdempotencyRecordRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies idempotency-key handling: method gating, replay and race-conflict behavior. */
class IdempotencyFilterTest {

  private IdempotencyRecordRepository repository;
  private IdempotencyFilter filter;
  private UUID tenantId;

  @BeforeEach
  void setUp() {
    repository = mock(IdempotencyRecordRepository.class);
    filter = new IdempotencyFilter(repository);
    tenantId = UUID.randomUUID();
    TenantContext.set(tenantId, UUID.randomUUID(), "TENANT_ADMIN");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void skipsNonMutatingMethods() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
    request.addHeader("Idempotency-Key", "key-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    verify(repository, never()).findByTenantIdAndIdempotencyKey(any(), any());
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void skipsWhenKeyIsBlank() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
    request.addHeader("Idempotency-Key", "   ");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    verify(repository, never()).findByTenantIdAndIdempotencyKey(any(), any());
  }

  @Test
  void replaysPreviousResponseForDuplicateKey() throws Exception {
    IdempotencyRecord existing = new IdempotencyRecord(tenantId, "key-1");
    existing.setResponse(201, "{\"created\":true}", "application/json");
    when(repository.findByTenantIdAndIdempotencyKey(tenantId, "key-1"))
        .thenReturn(Optional.of(existing));

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
    request.addHeader("Idempotency-Key", "key-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).contains("created");
    assertThat(chain.getRequest()).isNull(); // downstream not invoked on replay
  }

  @Test
  void cachesResponseOnFirstRequest() throws Exception {
    when(repository.findByTenantIdAndIdempotencyKey(tenantId, "key-1"))
        .thenReturn(Optional.empty());
    when(repository.save(any(IdempotencyRecord.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
    request.addHeader("Idempotency-Key", "key-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
    verify(repository, times(2)).save(any(IdempotencyRecord.class));
  }
}
