package com.awap.analytics.infrastructure.opensearch;

/** Minimal HTTP response returned by an {@link OpenSearchTransport}. */
public record OpenSearchResponse(int status, String body) {

  public OpenSearchResponse {
    body = body == null ? "" : body;
  }

  public boolean successful() {
    return status >= 200 && status < 300;
  }
}
