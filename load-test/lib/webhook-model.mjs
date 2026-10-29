/**
 * Pure retry model shared by the fake webhook target and the k6 retry scenario.
 * Kept dependency-free so `node --test` can assert the branches.
 */

/** Parses a Retry-After header (seconds or HTTP date) into seconds, or null. */
export function parseRetryAfter(value) {
  if (value === undefined || value === null || value === "") {
    return null;
  }
  const trimmed = String(value).trim();
  if (/^\d+$/.test(trimmed)) {
    return Number.parseInt(trimmed, 10);
  }
  const date = Date.parse(trimmed);
  if (Number.isNaN(date)) {
    return null;
  }
  return Math.max(0, Math.ceil((date - Date.now()) / 1000));
}

/**
 * Decides what a caller should do with a webhook response.
 * 2xx -> succeed, 429/5xx -> retry, anything else -> fail.
 */
export function decideAction(status, retryAfterHeader) {
  if (status >= 200 && status < 300) {
    return "succeed";
  }
  if (status === 429 || status >= 500) {
    return "retry";
  }
  return "fail";
}

/** Exponential backoff with the caller-provided jitter, capped at maxDelayMs. */
export function backoffDelay(attempt, baseMs = 100, jitterMs = 0, maxDelayMs = 5000) {
  const exponent = Math.max(0, attempt - 1);
  const base = baseMs * 2 ** exponent;
  const capped = Math.min(base, maxDelayMs);
  return jitterMs > 0 ? Math.min(capped + Math.floor(Math.random() * jitterMs), maxDelayMs) : capped;
}

/**
 * Deterministic failure pattern for the fake target: with rate 0.3 every third request succeeds,
 * which keeps repeated runs reproducible instead of relying on randomness.
 */
export function shouldFail(counter, rate) {
  if (rate <= 0) {
    return false;
  }
  const period = Math.max(1, Math.round(1 / Math.min(rate, 1)));
  return counter % period !== 0;
}

/** Next retry delay in seconds for a 429 that carries Retry-After. */
export function retryDelaySeconds(status, retryAfterHeader, attempt, baseMs) {
  if (status === 429) {
    const seconds = parseRetryAfter(retryAfterHeader);
    if (seconds !== null) {
      return seconds;
    }
  }
  return backoffDelay(attempt, baseMs) / 1000;
}
