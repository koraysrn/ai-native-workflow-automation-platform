# End-to-End Deployment — Definition of Done

This document defines the automated and manual evidence required to consider
the "hello world" end-to-end deploy of the preparation phase complete.

## Goal

A tagged release must build all three images, push them to GHCR, and deploy the
full stack to the `dev` Kubernetes cluster via Argo CD, with observability
proving a real request end to end.

## Pipeline flow

1. `Release` ([`.github/workflows/release.yml`](../.github/workflows/release.yml))
   bumps the version and creates a `v*` tag on `main`.
2. `CD` ([`.github/workflows/cd.yml`](../.github/workflows/cd.yml)) builds and
   pushes `backend`, `frontend` and `ai-layer` images via
   [`docker-build.yml`](../.github/workflows/docker-build.yml), then triggers an
   Argo CD sync of the `awap` application and waits for a healthy state.
3. Argo CD applies the manifests in [`infra/k8s`](../infra/k8s) (Kustomize) to
   the `dev` cluster.

## Acceptance evidence

- [ ] The `v*` tag exists and the CD run reports `argocd app wait awap --health`
      as successful.
- [ ] `kubectl -n awap get pods` shows `awap-backend` and `awap-frontend` in
      `Running` with `2/2` ready for the backend.
- [ ] `curl -fsS https://dev.awap.dev/actuator/health` returns `{"status":"UP"}`.
- [ ] A `TaskService` approve/reject increments `awap_task_decisions_total`
      (visible at `/actuator/prometheus`).
- [ ] Prometheus reports the backend target as `UP`
      (see [`infra/prometheus/prometheus.yml`](../infra/prometheus/prometheus.yml)).
- [ ] Grafana renders the `AWAP Overview` dashboard with non-empty series
      (see [`infra/grafana/provisioning/dashboards/awap.json`](../infra/grafana/provisioning/dashboards/awap.json)).
- [ ] A sample request produces a span in the OTel collector and the trace ID
      appears in the structured JSON log line (see
      [`docs/observability-dod.md`](observability-dod.md)).

## Verification script

Run against a reachable dev cluster:

```bash
kubectl -n awap get pods
kubectl -n awap wait --for=condition=Available deployment/awap-backend --timeout=300s
curl -fsS https://dev.awap.dev/actuator/health
```

> Note: secret material is provisioned from Sealed Secrets/Vault and is never
> committed in plain text (see [`infra/k8s/README.md`](../infra/k8s/README.md)).
