# Load Test

Repeatable multi-tenant concurrency load test for the AWAP platform.

## Tool

- k6 scenario: `load-test/k6/awap-load-test.js`.

## Run

```bash
k6 run load-test/k6/awap-load-test.js -e API_BASE=http://localhost:8080
```

## Model

- Seed 1000+ tenants, each with several users and one workflow instance.
- Concurrent operations: start workflows, claim/approve tasks (racing on the
  same task), submit forms, read own data.
- Assert strict tenant isolation in every response and single-winner approval.

## Thresholds

- `http_req_duration`: p95 < 2000ms.
- `http_req_failed`: rate < 0.05.
- The CI job fails the run when these thresholds are exceeded.

## Scheduling

The load test is intended to run nightly (not per PR) against the Docker Compose
stack.
