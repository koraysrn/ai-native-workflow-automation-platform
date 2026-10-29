package com.awap.analytics.infrastructure.opensearch;

import java.io.IOException;

/**
 * Port for OpenSearch HTTP calls. The production adapter uses the JDK {@link
 * java.net.http.HttpClient} while tests can substitute a deterministic fake without starting a
 * cluster.
 */
public interface OpenSearchTransport {

  OpenSearchResponse execute(String method, String path, String body)
      throws IOException, InterruptedException;
}
