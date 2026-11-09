# Content Security Policy

Issue #183 replaces the static `'unsafe-inline'`/`'unsafe-eval'` policy with a
per-request nonce policy generated in [`frontend/proxy.ts`](../frontend/proxy.ts).

## Policy

```
default-src 'self';
script-src 'self' 'nonce-<per-request>' 'strict-dynamic';
style-src 'self' 'nonce-<per-request>';
img-src 'self' data: blob:;
font-src 'self';
connect-src 'self' <API_ORIGINS>;
object-src 'none';
frame-ancestors 'none';
base-uri 'self';
form-action 'self';
report-uri /api/csp-report;
report-to csp-endpoint
```

- `unsafe-inline` and `unsafe-eval` are never emitted.
- Next.js reads the CSP request header and applies the nonce to its bootstrap
  scripts. Application code can read the nonce from the `x-nonce` request
  header when it must render an inline script or style.
- `connect-src` is driven by `API_ORIGINS`, the same variable the backend CORS
  allowlist uses, so the two cannot drift.
- `frame-ancestors 'none'`, `object-src 'none'`, `base-uri 'self'` and
  `form-action 'self'` stay locked down.

## Reporting

| Header | Value |
|---|---|
| `Report-To` | `{"group":"csp-endpoint","max_age":10886400,"endpoints":[{"url":"/api/csp-report"}]}` |
| `Reporting-Endpoints` | `csp-endpoint="/api/csp-report"` |

[`frontend/app/api/csp-report/route.ts`](../frontend/app/api/csp-report/route.ts)
accepts violation reports and **redacts** them before logging or forwarding:

- query strings and fragments are stripped from `document-uri`, `referrer`,
  `blocked-uri` and `source-file`;
- credentials in URLs are removed;
- `script-sample` is dropped entirely because it can contain form values or
  tokens;
- cookies and authorization fields are never copied.

Set `CSP_REPORT_COLLECTOR_URL` to forward redacted reports to an external
collector. The endpoint always answers `204`, so reporting cannot break a page.

## Report-only rollout

`CSP_REPORT_ONLY=true` switches the response to
`Content-Security-Policy-Report-Only` while keeping the same directives and
reporting headers. Staging should run report-only first, triage the redacted
violations, then switch to enforce mode. The Playwright suite in
[`frontend/e2e/security-headers.spec.ts`](../frontend/e2e/security-headers.spec.ts)
asserts the enforcing policy (nonce present, no `unsafe-*`, inline injection and
`eval` blocked).

## Allowed third-party origins

There are no third-party origins by default. The only allowed connect origins
are the `API_ORIGINS` values. Any future third-party script must be:

1. loaded by a nonce-trusted Next.js script (`strict-dynamic`), or
2. added to an explicit allowlist with an owner, purpose and review date in this
   document.

## Deployment checks

- Staging/production must not set `CSP_REPORT_ONLY=true`.
- Every HTML response must carry either `Content-Security-Policy` or
  `Content-Security-Policy-Report-Only` with a nonce.
- A missing nonce fails the Playwright security assertion.
