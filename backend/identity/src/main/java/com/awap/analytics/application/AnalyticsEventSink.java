package com.awap.analytics.application;

import java.util.Map;

/** Raw analytics event sink (OpenSearch in production, no-op by default). */
public interface AnalyticsEventSink {

  void write(Map<String, Object> document);
}
