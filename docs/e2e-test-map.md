# E2E test map (Playwright)

The suite lives in [`frontend/e2e`](../frontend/e2e) and runs in two modes:

- **PR mode** (`pnpm test:e2e`): the Next.js dev server is started by `webServer`, the API is
  mocked per spec, no database is required. Fast and deterministic.
- **Stack mode** (`E2E_LIVE_STACK=1`, the CI `e2e` job): `docker compose` provides Postgres/Kafka/
  MinIO plus the built backend and frontend; `e2e/global-setup.ts` waits for
  `GET /actuator/health` and the frontend, then seeds a tenant through `POST /api/v1/tenants`.

Both modes use one worker (fixtures stay deterministic) and retry once on CI with traces,
screenshots and video retained on failure.

| Acceptance criterion (issue #128 §6/#7) | Spec | Mode |
|---|---|---|
| Register a tenant and return to login | [`auth-journey.spec.ts`](../frontend/e2e/auth-journey.spec.ts) — `registration` | mocked |
| Wrong password shows an inline error and does not navigate | `auth-journey.spec.ts` — `login` | mocked |
| Login redirects to the dashboard | `auth-journey.spec.ts` — `login` | mocked |
| A user without an active tenant is routed to tenant selection | `auth-journey.spec.ts` — `session` | mocked |
| Protected routes redirect an expired session to login | `auth-journey.spec.ts` — `session` | mocked |
| Tenant isolation: the active tenant travels in `X-Tenant-Id` | [`tenant-isolation.spec.ts`](../frontend/e2e/tenant-isolation.spec.ts) | mocked |
| A cross-tenant rejection renders no foreign rows | `tenant-isolation.spec.ts` | mocked |
| No critical/serious axe violations on the shell | [`accessibility.spec.ts`](../frontend/e2e/accessibility.spec.ts) | mocked |
| CSP, HSTS and friends on every response | [`security-headers.spec.ts`](../frontend/e2e/security-headers.spec.ts) | mocked |
| Seeded tenant exists for live journeys | [`global-setup.ts`](../frontend/e2e/global-setup.ts) | stack |
| A DECISION node gets two conditional edges through the inspector | [`workflow-designer-conditions.spec.ts`](../frontend/e2e/workflow-designer-conditions.spec.ts) — `branches` | mocked |
| Save is blocked while an edge condition is invalid | `workflow-designer-conditions.spec.ts` — `guard` | mocked |
| The legend and axe checks pass on the designer | `workflow-designer-conditions.spec.ts` — `accessibility` | mocked |
| An upload flows through live status to PROMOTED and enables download | [`document-scan-live.spec.ts`](../frontend/e2e/document-scan-live.spec.ts) — `uploads` | mocked |
| Scan status updates rows without a refresh and gates download on PROMOTED | `document-scan-live.spec.ts` — `updates` | mocked |
| INFECTED shows an alert, an aria-live announcement and keeps download disabled | `document-scan-live.spec.ts` — `announces` | mocked |

## Conventions

- Seed values come from [`lib/e2eSeed.ts`](../frontend/lib/e2eSeed.ts); the slug/email include the
  CI run id so parallel or nightly runs never collide, while a repeated run with the same suffix
  reuses the tenant (409 is treated as success).
- No `test.skip` is allowed without a linked issue and an expiry date.
- Artifacts: `frontend/playwright-report` (HTML) and `frontend/test-results` (trace/video/screenshot)
  are uploaded by the `e2e` job on every run/failure respectively.
