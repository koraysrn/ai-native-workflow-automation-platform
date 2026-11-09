# Kubernetes manifests

GitOps-managed manifests for the AWAP platform. Argo CD renders one overlay per
environment from the `main` branch; manual `kubectl apply` is only used for local
kind clusters.

## Layout

```
infra/k8s/
├── base/                 # environment-independent resources
│   ├── backend.yaml
│   ├── frontend.yaml
│   ├── ai-layer.yaml
│   ├── hpa.yaml
│   ├── ingress.yaml
│   ├── pg-backup-cronjob.yaml
│   ├── minio-replication-cronjob.yaml
│   ├── secrets.yaml
│   └── kustomization.yaml
├── overlays/
│   ├── dev/              # awap-dev, dev.awap.local, 1 replica
│   ├── staging/          # awap-staging, staging.awap.local, 2 replicas
│   └── prod/             # awap-prod, awap.example.com, 3 replicas
├── argocd/applicationset.yaml
├── scripts/validate-overlays.mjs
└── debezium-connect.yaml
```

The base contains only values that are identical everywhere. Namespace, host,
TLS secret, replicas, HPA bounds, image tags and feature flags live in the
overlays.

Workloads declare CPU/memory requests and limits, HPA scale behavior,
topology-spread constraints and PodDisruptionBudgets. See
[`docs/capacity-planning.md`](../../docs/capacity-planning.md).

| Environment | Namespace | Host | Backend replicas | HPA |
|---|---|---|---:|---|
| dev | `awap-dev` | `dev.awap.local` | 1 | 1–3 |
| staging | `awap-staging` | `staging.awap.local` | 2 | 2–6 |
| prod | `awap-prod` | `awap.example.com` | 3 | 3–12 |

## Argo CD

[`argocd/applicationset.yaml`](argocd/applicationset.yaml) generates one
Application per environment (`awap-dev`, `awap-staging`, `awap-prod`). Each
application points at `infra/k8s/overlays/<env>` and targets the matching
namespace. The old single `argo-application.yaml` is removed to avoid two
applications managing the same manifests.

## Validate locally

```bash
node --test "infra/k8s/scripts/test/*.test.mjs"
node infra/k8s/scripts/validate-overlays.mjs --kubeconform
```

`kubectl kustomize infra/k8s/overlays/dev` renders an overlay manually. CI runs
`kustomize build` and `kubeconform` for all three overlays, then applies the
non-secret documents to a throwaway kind cluster with a server-side dry run.

## Promotion

1. Merge to `main`; Argo CD syncs `dev` first.
2. Verify the dev health endpoints and smoke tests.
3. Promote the image tags in `overlays/staging/kustomization.yaml` (or let the
   image updater do it), merge, and verify staging.
4. Promote the same immutable tags to `overlays/prod/kustomization.yaml`.
5. Never copy a production host, replica count, TLS secret or feature flag into
   a lower overlay; the validation script fails on drift.

## Secrets

Secrets are delivered through the mechanism documented in the base
`secrets.yaml` and the secret-rotation runbook. Plain-text `Secret` manifests
must never be committed. Issue #188 replaces the current placeholders with
deployable ciphertext / an external secret operator and adds a preflight check.
