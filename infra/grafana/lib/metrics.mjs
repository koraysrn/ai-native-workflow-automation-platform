/** Prometheus name -> catalog base name (histogram/counter suffixes are normalised). */
export function metricBase(name) {
  return name.replace(/_(bucket|total|count|sum)$/, "");
}

/** Extracts the metric names referenced by a PromQL expression. */
export function metricTokens(expr) {
  const matches =
    String(expr).match(/\b(?:awap_[a-z0-9_]+|http_server_requests_seconds_[a-z]+|up)\b/g) ?? [];
  return [...new Set(matches)];
}
