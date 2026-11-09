import {
  buildCsp,
  CSP_REPORT_TO,
  cspHeaderName,
  generateNonce,
  redactCspReport,
  redactUrl,
} from "../csp";

describe("csp", () => {
  it("generates a URL-safe nonce with the requested entropy", () => {
    const nonce = generateNonce((bytes) => bytes.fill(255));
    expect(nonce).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(nonce.length).toBe(22);
    expect(generateNonce()).not.toBe(generateNonce());
  });

  it("builds a nonce policy without unsafe-inline or unsafe-eval", () => {
    const csp = buildCsp({ nonce: "abc123", apiOrigins: "https://api.example.com" });
    expect(csp).toContain("script-src 'self' 'nonce-abc123' 'strict-dynamic'");
    expect(csp).toContain("style-src 'self' 'nonce-abc123'");
    expect(csp).toContain("connect-src 'self' https://api.example.com");
    expect(csp).toContain("report-uri /api/csp-report");
    expect(csp).toContain("report-to csp-endpoint");
    expect(csp).not.toContain("unsafe-inline");
    expect(csp).not.toContain("unsafe-eval");
  });

  it("uses the report-only header name when requested", () => {
    expect(cspHeaderName(false)).toBe("Content-Security-Policy");
    expect(cspHeaderName(true)).toBe("Content-Security-Policy-Report-Only");
    expect(CSP_REPORT_TO).toContain('"url":"/api/csp-report"');
  });

  it("redacts query strings and credentials from report URLs", () => {
    expect(redactUrl("https://user:pass@example.com/page?token=secret#frag")).toBe(
      "https://example.com/page",
    );
    expect(redactUrl("/relative?token=secret")).toBe("/relative");
  });

  it("drops script samples and sensitive fields from the report", () => {
    const redacted = redactCspReport({
      "csp-report": {
        "document-uri": "https://app.example.com/tasks?token=abc",
        "blocked-uri": "https://evil.example.com/x?y=1",
        "script-sample": "document.cookie",
        "violated-directive": "script-src",
        "effective-directive": "script-src-elem",
        "line-number": 12,
        "column-number": 3,
        cookie: "awap_session=1",
        authorization: "Bearer secret",
      },
    });
    const report = redacted["csp-report"] as Record<string, unknown>;
    expect(report["document-uri"]).toBe("https://app.example.com/tasks");
    expect(report["blocked-uri"]).toBe("https://evil.example.com/x");
    expect(report["script-sample"]).toBe("[redacted]");
    expect(report["line-number"]).toBe(12);
    expect(report).not.toHaveProperty("cookie");
    expect(report).not.toHaveProperty("authorization");
    expect(JSON.stringify(redacted)).not.toContain("token=abc");
  });

  it("handles malformed reports without throwing", () => {
    expect(() => redactCspReport(null)).not.toThrow();
    expect(redactCspReport("nope")).toHaveProperty("csp-report");
  });
});
