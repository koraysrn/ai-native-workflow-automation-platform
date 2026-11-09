/**
 * Content Security Policy helpers (issue #183).
 *
 * Pure functions so the nonce generation, header construction and report
 * redaction can be unit-tested without a running Next.js server.
 */

export const CSP_REPORT_URI = "/api/csp-report";
export const CSP_REPORT_GROUP = "csp-endpoint";

/** Builds the `Report-To` header value for the CSP collector. */
export const CSP_REPORT_TO = JSON.stringify({
  group: CSP_REPORT_GROUP,
  max_age: 10886400,
  endpoints: [{ url: CSP_REPORT_URI }],
});

/** Builds the `Reporting-Endpoints` header value. */
export const CSP_REPORTING_ENDPOINTS = `${CSP_REPORT_GROUP}="${CSP_REPORT_URI}"`;

export interface CspOptions {
  nonce: string;
  /** Space-separated API origins allowed by `connect-src`. */
  apiOrigins: string;
  reportUri?: string;
}

export type RandomValues = (bytes: Uint8Array) => Uint8Array;

/**
 * Generates a per-request base64url nonce. `crypto.getRandomValues` is
 * available in the Edge runtime and in Node 20+, so the same implementation
 * runs in middleware and tests.
 */
export function generateNonce(
  randomValues: RandomValues = (bytes) => crypto.getRandomValues(bytes),
  byteLength = 16,
): string {
  const bytes = randomValues(new Uint8Array(byteLength));
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function cspHeaderName(reportOnly: boolean): string {
  return reportOnly
    ? "Content-Security-Policy-Report-Only"
    : "Content-Security-Policy";
}

/**
 * Builds a nonce-based policy. `strict-dynamic` lets nonce-trusted Next.js
 * bootstrap scripts load their chunks, while `unsafe-inline` and `unsafe-eval`
 * are never emitted.
 */
export function buildCsp({ nonce, apiOrigins, reportUri = CSP_REPORT_URI }: CspOptions): string {
  const origins = apiOrigins.trim() || "'self'";
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'`,
    `style-src 'self' 'nonce-${nonce}'`,
    "img-src 'self' data: blob:",
    "font-src 'self'",
    `connect-src 'self' ${origins}`,
    "object-src 'none'",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    `report-uri ${reportUri}`,
    `report-to ${CSP_REPORT_GROUP}`,
  ].join("; ");
}

/** Removes query strings, fragments and credentials from a URL-shaped value. */
export function redactUrl(value: string): string {
  try {
    const url = new URL(value, "https://redacted.invalid");
    url.search = "";
    url.hash = "";
    url.username = "";
    url.password = "";
    return value.startsWith("http") ? url.toString() : `${url.pathname}`;
  } catch {
    return "[redacted]";
  }
}

const REPORT_STRING_FIELDS = [
  "document-uri",
  "referrer",
  "blocked-uri",
  "violated-directive",
  "effective-directive",
  "original-policy",
  "disposition",
  "source-file",
] as const;

const REPORT_NUMBER_FIELDS = ["status-code", "line-number", "column-number"] as const;

/**
 * Sanitizes a CSP report before logging or forwarding it. Query strings,
 * fragments, credentials, cookies and authorization material never leave the
 * process, and script samples are dropped entirely.
 */
export function redactCspReport(payload: unknown): Record<string, unknown> {
  const raw =
    payload && typeof payload === "object" && "csp-report" in payload
      ? (payload as { "csp-report": unknown })["csp-report"]
      : payload;
  if (!raw || typeof raw !== "object") {
    return { "csp-report": { "violated-directive": "unknown", "blocked-uri": "[redacted]" } };
  }
  const source = raw as Record<string, unknown>;
  const sanitized: Record<string, unknown> = {};
  for (const field of REPORT_STRING_FIELDS) {
    const value = source[field];
    if (typeof value === "string") {
      sanitized[field] =
        field === "document-uri" || field === "referrer" || field === "blocked-uri" || field === "source-file"
          ? redactUrl(value)
          : value;
    }
  }
  for (const field of REPORT_NUMBER_FIELDS) {
    const value = source[field];
    if (typeof value === "number" && Number.isFinite(value)) {
      sanitized[field] = value;
    }
  }
  // Script samples can contain form values or tokens; never log them.
  sanitized["script-sample"] = "[redacted]";
  sanitized["csp-report"] = "redacted";
  return { "csp-report": sanitized, timestamp: new Date().toISOString() };
}
