package com.awap.analytics.infrastructure.opensearch;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Scriptable fake transport used by the unit tests to exercise every sink branch. */
final class FakeOpenSearchTransport implements OpenSearchTransport {

  record Request(String method, String path, String body, long atNanos) {}

  private final Deque<Object> scripted = new ArrayDeque<>();
  private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
  private volatile OpenSearchResponse defaultResponse =
      new OpenSearchResponse(200, "{\"errors\":false}");
  private volatile IOException defaultError;

  synchronized void enqueue(Object responseOrError) {
    scripted.addLast(responseOrError);
  }

  void defaultResponse(OpenSearchResponse response) {
    this.defaultResponse = response;
  }

  void defaultError(IOException error) {
    this.defaultError = error;
  }

  List<Request> requests() {
    synchronized (requests) {
      return List.copyOf(requests);
    }
  }

  @Override
  public OpenSearchResponse execute(String method, String path, String body)
      throws IOException, InterruptedException {
    requests.add(new Request(method, path, body, System.nanoTime()));
    Object next;
    synchronized (this) {
      next = scripted.pollFirst();
    }
    if (next instanceof OpenSearchResponse response) {
      return response;
    }
    if (next instanceof IOException error) {
      throw error;
    }
    if (next instanceof RuntimeException error) {
      throw error;
    }
    IOException error = defaultError;
    if (error != null) {
      throw error;
    }
    return defaultResponse;
  }
}
