#!/usr/bin/env sh
#
# Plain-text secret gate (issue #98, "secrets" item).
#
# Fails the build when a plain-text secret leaks into the repository. The primary
# defence is SealedSecrets in infra/k8s/secrets.yaml; this script is the
# defense-in-depth check that prod configuration must come from Vault / a secret
# store rather than from committed plain text.
#
# Usage:
#   scripts/check-secrets.sh
#
# Exits 0 when clean, 1 when a suspicious plain-text secret is detected.
set -eu

cd "$(dirname "$0")/.."

FAILED=0

# 1. SealedSecret manifests must never contain raw `stringData` (plain text).
if grep -rEn '^\s+stringData:' infra/k8s 2>/dev/null; then
  echo "check-secrets: FAIL plain-text stringData found in Kubernetes manifests" >&2
  FAILED=1
fi

# 2. No committed plain-text password/secret literals outside allowed env examples.
#    Allowed: infra/.env.example (documented placeholders only) and *.md docs.
HITS=$(grep -rInE '(password|secret|token|api[-_]?key)\s*[:=]\s*["'"'"'][^"'"'"'$<>]{6,}' \
  --include='*.yml' --include='*.yaml' --include='*.properties' --include='*.env' \
  --exclude='.env.example' \
  . 2>/dev/null | grep -v 'docs/' || true)

if [ -n "$HITS" ]; then
  echo "check-secrets: FAIL possible plain-text secret literals:" >&2
  printf '%s\n' "$HITS" >&2
  FAILED=1
fi

# 3. Run gitleaks full-history scan when available.
if command -v gitleaks >/dev/null 2>&1; then
  if ! gitleaks detect --source . --redact --verbose; then
    echo "check-secrets: FAIL gitleaks detected secrets" >&2
    FAILED=1
  fi
else
  echo "check-secrets: gitleaks not installed; skipping full-history scan (CI runs it)"
fi

if [ "$FAILED" -ne 0 ]; then
  echo "check-secrets: FAILED" >&2
  exit 1
fi

echo "check-secrets: OK no plain-text secrets committed"
