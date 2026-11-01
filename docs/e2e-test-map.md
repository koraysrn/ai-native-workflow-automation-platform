# E2E test map (Playwright)

The suite lives in [`frontend/e2e`](../frontend/e2e) and runs in two modes:

- **PR mode** (`pnpm test:e2e`): the Next.js dev server is started by `webServer`, the API is
  mocked per spec, no database is required. Fast and deterministic. The live journey spec is
  ignored by [`playwright.config.ts`](../frontend/playwright.config.ts) unless
  `E2E_LIVE_STACK=1`.
- **Stack mode** (`E2E_LIVE_STACK=1`, the CI `e2e` job): `docker compose` provides Postgres/Kafka
  plus the built backend and frontend; `e2e/global-setup.ts` waits for `GET /actuator/health` and
  the frontend, then seeds a tenant through `POST /api/v1/tenants`. The job also sets
  `KAFKA_ENABLED=true` so the outbox relay delivers the analytics events, and
  `FILE_MANAGE_BUCKETS=false` because MinIO is an opt-in profile. `FILE_SCAN_INTERVAL_MS` is
  raised so the document assertion sees the real `QUARANTINED` projection instead of a timed scan.

Both modes use one worker (fixtures stay deterministic) and retry once on CI with traces,
screenshots and video retained on failure.

| Acceptance criterion (issue #128 §6/#7, issue #158) | Spec | Mode |
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
| A user logs in, selects the seeded tenant and sees the dashboard counts from the real API | [`live-journey.spec.ts`](../frontend/e2e/live-journey.spec.ts) — `dashboard` | stack |
| A published workflow starts and the instance reaches COMPLETED | `live-journey.spec.ts` — `workflow` | stack |
| A task is approved and the API status becomes APPROVED | `live-journey.spec.ts` — `task` | stack |
| A new document is QUARANTINED and download stays disabled | `live-journey.spec.ts` — `document` | stack |
| Analytics aggregates include WORKFLOW_COMPLETED and the page renders the real count | `live-journey.spec.ts` — `analytics` | stack |
| A DECISION node gets two conditional edges through the inspector | [`workflow-designer-conditions.spec.ts`](../frontend/e2e/workflow-designer-conditions.spec.ts) — `branches` | mocked |
| Save is blocked while an edge condition is invalid | `workflow-designer-conditions.spec.ts` — `guard` | mocked |
| The legend and axe checks pass on the designer | `workflow-designer-conditions.spec.ts` — `accessibility` | mocked |
| An upload flows through live status to PROMOTED and enables download | [`document-scan-live.spec.ts`](../frontend/e2e/document-scan-live.spec.ts) — `uploads` | mocked |
| Scan status updates rows without a refresh and gates download on PROMOTED | `document-scan-live.spec.ts` — `updates` | mocked |
| INFECTED shows an alert, an aria-live announcement and keeps download disabled | `document-scan-live.spec.ts` — `announces` | mocked |

## Conventions

- Seed values come from [`lib/e2eSeed.ts`](../frontend/lib/e2eSeed.ts); the live journey and the
  global setup both call `seedPlan()` with the same `E2E_SEED_SUFFIX`, so the slug/email include
  the CI run id and parallel or nightly runs never collide. A repeated run with the same suffix
  reuses the tenant (409 is treated as success).
- The live journey is environment-gated rather than skipped: `playwright.config.ts` excludes it
  from the dependency-free PR run, so no `test.skip` is introduced.
- No `test.skip` is allowed without a linked issue and an expiry date.
- Artifacts: `frontend/playwright-report` (HTML) and `frontend/test-results` (trace/video/screenshot)
  are uploaded by the `e2e` job on every run/failure respectively; the live journey runs in the
  same job, so its traces and videos use the same pipeline.
