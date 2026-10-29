package com.awap.analytics.infrastructure.opensearch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** {@link OpenSearchTransport} backed by the JDK HTTP client with strict connect/read timeouts. */
public final class JdkOpenSearchTransport implements OpenSearchTransport {

  private final String endpoint;
  private final HttpClient httpClient;
  private final Duration requestTimeout;

  public JdkOpenSearchTransport(OpenSearchProperties properties) {
    this.endpoint = stripTrailingSlash(properties.endpoint());
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
            .build();
    this.requestTimeout = Duration.ofMillis(properties.requestTimeoutMs());
  }

  @Override
  public OpenSearchResponse execute(String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(requestTimeout);
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    HttpResponse<String> response =
        httpClient.send(
            builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new OpenSearchResponse(response.statusCode(), response.body());
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
