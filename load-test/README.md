# Load Test

Repeatable multi-tenant load coverage for the AWAP platform, driven by k6 against the Docker
Compose stack. The suite covers workflow starts, analytics queries, document upload/list,
notification fan-out and webhook retry pressure.

## Scenarios

| k6 scenario | Flow | Threshold |
|---|---|---|
| `multi_tenant_ramp` | `/actuator/health` + `/api/v1/workflow-instances` ramp 1 → 200 VUs | `http_req_failed < 5%`, p95 < 2s |
| `outbox_relay_burst` | concurrent workflow starts through the outbox relay | p95 < 2s |
| `analytics_ingestion` | `/api/v1/analytics/summary` at 50 rps | p95 < 2s |
| `document_upload_download` | document presign + listing | presign p95 < 500ms |
| `notification_fanout` | inbox + unread count at 25 rps | p95 < 2s |
| `webhook_retry_pressure` | retries against the fake webhook target | `webhook_retry_lag` p95 < 10s |

Custom trends: `analytics_query_latency`, `document_presign_duration`, `document_scan_duration`,
`notification_fanout_latency`, `webhook_retry_lag`, plus the `webhook_retries` counter.

## Run locally

```bash
# 1. Stack
cd infra && docker compose up -d --build && cd ..

# 2. Fake webhook target (local-only; never point the test at a real host)
FAILURE_RATE=0.3 FAILURE_STATUS=500 LATENCY_MS=25 node load-test/fake-webhook.mjs &

# 3. Seed a tenant and export the ids/token (also written to load-test/seed.json)
node load-test/seed.mjs
set -a; . <(node -e 'const s=require("./load-test/seed.json").tenants[0];
console.log(`TENANT_ID=${s.tenantId}\nACCESS_TOKEN=${s.token}\nTENANT_SLUG=${s.slug}`)'); set +a

# 4. Full nightly profile
k6 run load-test/k6/awap-load-test.js \
  -e API_BASE=http://localhost:8080 -e WEBHOOK_TARGET=http://localhost:9099/webhook

# 5. PR smoke profile (low VUs, under five minutes)
K6_SMOKE=1 k6 run load-test/k6/awap-load-test.js -e API_BASE=http://localhost:8080
```

`GOFLAGS`/`k6` are not required for the static checks:

```bash
node --test "load-test/test/*.test.mjs"   # scenario/threshold and retry-model unit tests
```

## Fake webhook target

| Env var | Default | Behaviour |
|---|---|---|
| `PORT` | `9099` | Listen port |
| `LATENCY_MS` | `25` | Artificial latency before responding |
| `FAILURE_RATE` | `0.3` | Deterministic failure pattern (every third request succeeds at 0.3) |
| `FAILURE_STATUS` | `500` | `500` retry path; set `429` to exercise `Retry-After` |
| `RETRY_AFTER_SECONDS` | `1` | `Retry-After` value used for 429 responses |

`GET /health` reports the active configuration so the CI job can assert the target is up.

## Seeding and cleanup

- Slugs are `load-<suffix>-<index>` where the suffix is `LOAD_TEST_SUFFIX` or a timestamp+pid, so
  repeated runs never collide and the smoke/full profiles are idempotent.
- `load-test/seed.json` holds the seeded ids/token and is ignored by git.
- Tenants cannot be deleted through the API; tear the fixtures down with the stack:

```sql
-- Only for the throwaway load-test database.
DELETE FROM notification_deliveries WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'load-%');
DELETE FROM workflow_instances WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'load-%');
DELETE FROM tenants WHERE slug LIKE 'load-%';
```

## Scheduling

- `.github/workflows/load-test-nightly.yml` runs the full profile nightly and on demand.
- PRs that touch `load-test/**` always run the static checks; the low-VU smoke profile (which boots
  the full Compose stack) runs on PRs only when the `LOAD_TEST_SMOKE_ENABLED` repository variable is
  `true`, and nightly/manual runs always execute the full profile.
- k6 JSON summaries are uploaded as artifacts; a threshold breach fails the job.
