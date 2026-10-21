#!/usr/bin/env bash
# Applies repository governance to GitHub: squash merge, label taxonomy,
# milestones and branch protection (settings/branch-protection.json). Idempotent.
#
# Requires the GitHub CLI: https://cli.github.com
#   gh auth login
#   ./scripts/apply-repo-governance.sh [owner/repo]
set -euo pipefail

REPO="${1:-koraysrn/ai-native-workflow-automation-platform}"
POLICY="settings/branch-protection.json"

if ! command -v gh >/dev/null 2>&1; then
  echo "ERROR: GitHub CLI (gh) is not installed." >&2
  echo "Install it first: https://cli.github.com" >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "ERROR: gh is not authenticated. Run 'gh auth login' first." >&2
  exit 1
fi

echo "== Applying repository governance to $REPO =="

echo "[1/4] Merge policy: squash merge + head-branch auto-delete"
gh api -X PATCH "repos/$REPO" \
  -f allow_squash_merge=true \
  -f allow_merge_commit=false \
  -f allow_rebase_merge=false \
  -f delete_branch_on_merge=true >/dev/null

echo "[2/4] Label taxonomy"
while read -r name desc color; do
  [ -n "$name" ] || continue
  gh label create "$name" --description "$desc" --color "$color" --force >/dev/null || true
done <<'EOF'
kind/bug "Bug fix or correction" d73a4a
kind/feature "New feature or request" a2eeef
kind/chore "Maintenance, tooling or infra work" c5def5
area/backend "Backend (Java / Spring Boot)" ffa221
area/frontend "Frontend (Next.js / React)" 168700
area/ai "AI layer (Python / FastAPI)" 5319e7
priority/p0 "Critical - must ship first" b60205
priority/p1 "High priority" d93f0b
priority/p2 "Medium priority" fbca04
priority/p3 "Low priority" 0e8a16
phase/preparation "Preparation phase (repo, CI/CD, security foundations)" e4e669
phase/core-mvp "Core MVP phase" 0e8a16
phase/expansion "Post-MVP expansion" 0075ca
EOF

echo "[3/4] Milestones"
ensure_milestone() {
  local title="$1" description="$2"
  if gh api "repos/$REPO/milestones" --jq '.[].title' | grep -qx "$title"; then
    echo "  - already exists: $title"
  else
    gh api "repos/$REPO/milestones" -f title="$title" -f description="$description" >/dev/null
    echo "  - created: $title"
  fi
}
ensure_milestone "Preparation Phase" "Preparation phase - repo, CI/CD and security foundations"
ensure_milestone "Phase 0 - Core MVP" "Phase 0 - core MVP"
ensure_milestone "Phase 1 - Expansion" "Phase 1 - post-MVP expansion"

echo "[4/4] Branch protection (main)"
if [ ! -f "$POLICY" ]; then
  echo "ERROR: missing policy $POLICY" >&2
  exit 1
fi
gh api -X PUT "repos/$REPO/branches/main/protection" \
  -H "Accept: application/vnd.github+json" \
  --input "$POLICY" >/dev/null

echo "== Done =="
echo "Verify with: gh api repos/$REPO/branches/main/protection"
