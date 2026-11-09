# Secret rotation runbook

Issue #188 standardises secret delivery on **External Secrets Operator (ESO) +
Vault**. The committed manifests contain no plaintext and no placeholder
ciphertext. This runbook covers bootstrap, rotation, verification and rollback.

## Source of truth

Vault KV v2 paths:

| Environment | Backend | Infra | DR |
|---|---|---|---|
| dev | `secret/awap-dev/backend` | `secret/awap-dev/infra` | `secret/awap-dev/dr` |
| staging | `secret/awap-staging/backend` | `secret/awap-staging/infra` | `secret/awap-staging/dr` |
| prod | `secret/awap-prod/backend` | `secret/awap-prod/infra` | `secret/awap-prod/dr` |

Required keys:

- backend: `db-url`, `db-user`, `db-password`, `jwt-secret`,
  `minio-access-key`, `minio-secret-key`;
- infra: `minio-root-user`, `minio-root-password`;
- dr: `minio-endpoint`, `minio-access-key`, `minio-secret-key`.

## Bootstrap

1. Install the External Secrets Operator and its CRDs in the cluster.
2. Enable Vault Kubernetes auth for the `awap-<env>` role and bind the
   `awap-external-secrets` service account in the environment namespace.
3. Seed every key in the table above from the deployment pipeline. Do not write
   plaintext to Git.
4. Apply the overlay; ESO creates `awap-backend-secrets`, `awap-infra-secrets`
   and `awap-dr-secrets` in the namespace.
5. Run the preflight:

   ```bash
   node infra/k8s/scripts/check-secret-keys.mjs
   ```

## Rotation

Use overlapping credentials where the dependency supports it (for example a
second DB user or MinIO key). Then:

1. Write the new value to the Vault path.
2. Force ESO to reconcile immediately (or wait for the 1h refresh):

   ```bash
   kubectl -n awap-prod annotate externalsecret awap-backend-secrets \
     force-sync="$(date +%s)" --overwrite
   ```

3. Wait for ESO to report `SecretSynced`:

   ```bash
   kubectl -n awap-prod get externalsecret awap-backend-secrets
   ```

4. Roll the workloads so they read the new Secret:

   ```bash
   kubectl -n awap-prod rollout restart deployment/awap-backend
   kubectl -n awap-prod rollout status deployment/awap-backend
   ```

5. Verify the preflight and the health endpoint. For the backend, also verify
   that the old credential is rejected by the dependency (for example a
   connection with the previous DB password fails).

## Pre-deploy key check

[`infra/k8s/scripts/check-secret-keys.mjs`](../infra/k8s/scripts/check-secret-keys.mjs)
renders every overlay and fails when:

- a workload `secretKeyRef` has no matching ExternalSecret `data` entry;
- an ExternalSecret uses `dataFrom` (opaque keys cannot be preflighted).

CI runs it in the `Kubernetes Overlays` job before the kind dry run.

## Rollback

If the dependency rejects the new credential:

1. Restore the previous value in Vault.
2. Force-sync the ExternalSecret and restart the workload.
3. Keep the overlapping credential until the rollback is verified, then remove
   it.

## Rotation cadence

- JWT signing key: at least every 180 days and after any suspected exposure.
- Database and MinIO credentials: at least every 90 days.
- DR credentials: at least every 90 days, coordinated with the replication job.
- Any leaked value: rotate immediately, then run a gitleaks full-history scan
  and follow the incident process.
