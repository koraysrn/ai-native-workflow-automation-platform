# 1000+ tenant load test: methodology and SLOs (issue #201)

The platform must prove that concurrent tenant traffic, approvals and row-level security (RLS) do
not corrupt state or violate isolation. This document defines how the benchmark is produced, which
budgets gate it, and how a dated result is published.

## What the run proves

| Assertion | Where it is enforced |
|---|---|
| 1000+ tenants run concurrently through the same backend | `tenant_isolation` ramps to one VU per seeded tenant (`load-test/k6/lib/tenancy-scenarios.mjs`) |
| A tenant's list contains only its own rows | `assertOwnRows()` on every iteration (`load-test/lib/tenancy.mjs`), violation counter `isolation_violations` |
| A cross-tenant instance read is denied | probe with a neighbour tenant's instance id; 403/404 expected, any 2xx increments `cross_tenant_leaks` |
| Postgres RLS hides other tenants even for a direct id lookup | `load-test/verify-isolation.sql`, run as the non-superuser `awap_app` role |
| A double approval yields exactly one decision | five concurrent `POST /tasks/{id}/approve` calls; one 200 and four 409s (`classifyApprovalRace`) |
| A replayed `Idempotency-Key` never creates a second instance | two identical starts with the same key; replay or 409 accepted, a new id is a failure |
| Latency and error budgets hold | k6 thresholds derived from the same SLO catalogue the reporter uses (`load-test/lib/slo.mjs`) |

## Topology

- The backend, Postgres, Redis and Kafka run from `infra/docker-compose.yml`; k6 runs on the runner
  so `localhost:8080` reaches the Compose stack.
- The run is sized for the runner, not for production capacity. Results are comparative: they prove
  correctness and catch regressions between builds. Sizing guidance lives in
  [`docs/capacity-planning.md`](../capacity-planning.md).
- **General rate limiting:** `RATE_LIMIT_ENABLED` is `false` in the Compose profile. A single k6
  host represents 1000 tenants, while the general limiter keys on client IP (300 requests per
  minute), so an enabled limiter would measure the limiter, not the platform.
- **Auth rate limiting:** `/auth/login` is limited to 10 requests per 60s per client IP. Seeding
  1000 tenants from one host would take about 100 minutes with that budget, so the load-test
  Compose invocation sets `AUTH_RATE_LIMIT_ENABLED=false` (default `true`, produced by
  `awap.auth-rate-limit.*`). The limit is also configurable
  (`AUTH_RATE_LIMIT_LIMIT`) and covered by `AuthRateLimitFilterTest`. Any deployed environment
  keeps the defaults; lifting it is a load-test-only decision, and the harness's own retry/timeout
  behaviour is unaffected.
- **Seeding pace:** the seeder bounds each request at 10s (`--timeout`) and retries transport
  failures, so a stalled keep-alive socket cannot stall a 1000-tenant run. Access tokens live 15
  minutes, so a long seed finishes with `--refresh`, which re-authenticates the stored tenants
  (registration and instances are reused) before the k6 run.

## Reference measurement (2026-11-11)

[`tenant-load-2026-11-11.json`](tenant-load-2026-11-11.json) is the committed reference run: the
smoke profile executed against a freshly seeded **1000-tenant** grid on the Compose stack
(3 isolation VUs, 3 race iterations, 3 idempotency iterations). All 13 applicable checks passed:

| Check | Measured | Budget |
|---|---|---|
| Tenant isolation p95 / p99 | 4.7 ms / 6.4 ms | 2000 / 4000 ms |
| Approval race p95 / p99 | 25.3 ms / 28.7 ms | 1500 / 3000 ms |
| Idempotent start p95 / p99 | 49.4 ms / 54.3 ms | 2000 / 4000 ms |
| Error rates (all scenarios) | 0 | < 1% |
| Leaked rows / cross-tenant reads / race losses / duplicate instances | 0 / 0 / 0 / 0 | 0 |
| Postgres RLS as `awap_app` | passed | no foreign rows |

The full profile (ramp to 1000 VUs, 2-minute race and idempotency scenarios, throughput floor)
runs nightly and its dated report is attached to the run; commit that artifact as
`docs/benchmarks/tenant-load-<date>.json` for a release record.

## Seed model

`node load-test/seed-tenants.mjs --tenants 1000` creates, per tenant:

1. a tenant `load-<suffix>-<index>` with an admin user;
2. an authenticated session plus the CSRF cookie pair the browser would use;
3. one published `START -> END` workflow definition;
4. one started workflow instance (the cross-tenant probe target).

The credential list is written to `load-test/tenants.json` (gitignored) and read by k6 through
`TENANTS_FILE` (an absolute path, because k6 resolves `open()` relative to the script). Seeding is
concurrent (`--concurrency`), retries 429/5xx/transport failures, stops scheduling after five
failures and keeps the successfully seeded tenants so a partial run is still diagnosable.
`--refresh` re-authenticates the stored tenants without re-registering them. `--dry-run` validates
the plan and the 1000-tenant grid without touching the API.

## Scenario and SLO matrix

| Scenario | Executor (full) | Measurement | SLO |
|---|---|---|---|
| `tenant_isolation` | ramping VUs to the tenant count (capped at 1000) | own-list read, cross-tenant probe | p95 < 2000 ms, p99 < 4000 ms, error rate < 1%, zero leaked rows |
| `approval_race` | 50 constant VUs for 2 min | task create + five parallel approvals | p95 < 1500 ms, p99 < 3000 ms, error rate < 1%, zero lost races |
| `idempotent_start` | 50 constant VUs for 2 min | double start with one key | p95 < 2000 ms, p99 < 4000 ms, error rate < 1%, zero duplicate instances |

The catalogue also gates overall throughput (>= 20 req/s) and four zero-tolerance counters:
`isolation_violations`, `cross_tenant_leaks`, `approval_race_violations`, `idempotency_violations`.

Budgets are product-derived defaults from the capacity plan; change them in
`load-test/lib/slo.mjs` only, which updates both the k6 thresholds and the report.

## Running it

```bash
# 1. Core stack (Kafka keeps the outbox relay path live)
cd infra && docker compose up -d --build postgres redis kafka schema-registry backend && cd ..

# 2. Plan validation (no API calls)
node load-test/seed-tenants.mjs --tenants 1000 --dry-run

# 3. Seed the grid
API_BASE=http://localhost:8080 \
  node load-test/seed-tenants.mjs --tenants 1000 --concurrency 16 --suffix local-$(date +%s)

# 4. Load (full profile), then evaluate the SLOs
k6 run load-test/k6/awap-tenancy-load-test.js \
  --summary-export=load-test/results/tenancy-summary.json \
  -e API_BASE=http://localhost:8080 -e TENANTS_FILE="$PWD/load-test/tenants.json"
node load-test/report-slo.mjs \
  --summary load-test/results/tenancy-summary.json --tenants 1000 \
  --output load-test/results/tenancy-slo-report.json

# 5. Optional: PR-sized smoke profile (3 VUs, under a minute)
K6_SMOKE=1 k6 run load-test/k6/awap-tenancy-load-test.js \
  -e API_BASE=http://localhost:8080 -e TENANTS_FILE="$PWD/load-test/tenants.json"

# 6. Database-level RLS assertion (tenant ids from load-test/tenants.json)
docker compose -f infra/docker-compose.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U awap_app -d awap \
    -v tenant_id=<tenant-a> -v foreign_id=<tenant-b> -f /dev/stdin \
  < load-test/verify-isolation.sql
```

`node --test "load-test/test/*.test.mjs"` covers every script branch, including the dry runs CI
executes, so a broken CLI fails the static job before a stack is ever booted.

## Nightly automation and publication

`.github/workflows/load-test-nightly.yml` runs the tenancy suite nightly and on demand:

1. static job: unit tests, `seed-tenants.mjs --dry-run`, `report-slo.mjs --dry-run`, k6 bundling;
2. load job: boots the stack, seeds `LOAD_TEST_TENANT_COUNT` tenants (default 1000), runs k6,
   evaluates the SLOs into `load-test/results/tenancy-slo-report.json`, appends the table to the job
   summary and asserts RLS in Postgres as `awap_app`;
3. artifacts: k6 summary, SLO report and the existing nightly summaries (credentials are never
   uploaded).

The job is red when k6 crosses a threshold (exit 99) or when the report finds a regression
(exit 1). A nightly report is dated (`generatedAt`) and can be committed as
`docs/benchmarks/tenant-load-<date>.json` for a release record.

## Cleanup

Fixtures are `load-*` tenants in a throwaway database. Tear them down with the stack
(`docker compose down -v`) or the SQL block in [`load-test/README.md`](../../load-test/README.md).
