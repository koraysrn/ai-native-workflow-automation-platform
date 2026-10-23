# Kubernetes Manifests

GitOps-managed manifests for the AWAP platform. Applied by Argo CD from the
`main` branch; manual `kubectl apply` is only used for local kind clusters.

## Secrets

Secrets are provisioned exclusively through **Sealed Secrets** (see
[`secrets.yaml`](secrets.yaml)). The Sealed Secrets controller decrypts them
into the target `Secret` objects at deploy time. Plain-text `Secret` manifests
are forbidden and ignored by Git (see the repository root `.gitignore`).

### Sealing workflow

```bash
# One time: fetch the cluster controller certificate.
kubeseal --controller-namespace kube-system --fetch-cert > pub-cert.pem

# Seal a value (namespace-wide scope):
echo -n 'change-me' | kubeseal --cert pub-cert.pem --scope namespace-wide \
  --namespace awap --name awap-backend-secrets --raw
```

Paste the returned value into `spec.encryptedData` in
[`secrets.yaml`](secrets.yaml). The plain-text values are kept only in Vault
and are injected into the cluster by the GitOps pipeline, never committed.

## Components

| File | Purpose |
|---|---|
| [`namespace.yaml`](namespace.yaml) | `awap` and `awap-infra` namespaces. |
| [`backend.yaml`](backend.yaml) | Backend Deployment (2 replicas) + Service. |
| [`frontend.yaml`](frontend.yaml) | Frontend Deployment + Service. |
| [`hpa.yaml`](hpa.yaml) | Backend horizontal pod autoscaler (CPU 70%, 2–10). |
| [`ingress.yaml`](ingress.yaml) | Ingress routes for the frontend and API. |
| [`secrets.yaml`](secrets.yaml) | SealedSecret manifests (see above). |
| [`argo-application.yaml`](argo-application.yaml) | Argo CD application definition. |
| [`kustomization.yaml`](kustomization.yaml) | Kustomize entry point. |
