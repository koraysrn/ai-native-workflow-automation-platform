#!/usr/bin/env bash
#
# Enforces GitHub branch protection for `main` and detects drift (issue #178).
#
# The desired policy is defined exactly once: the REQUIRED_CHECKS array plus the
# rule values rendered by render_expected(). The committed snapshot
# settings/branch-protection.json is generated from `--expected` and validated by
# scripts/test/apply-branch-protection.test.mjs.
#
# Usage:
#   scripts/apply-branch-protection.sh --dry-run  [--repo owner/repo]
#   scripts/apply-branch-protection.sh --apply    [--repo owner/repo]
#   scripts/apply-branch-protection.sh --verify   [--repo owner/repo]
#   scripts/apply-branch-protection.sh --expected
#   scripts/apply-branch-protection.sh --list-paths
#
# Modes:
#   --dry-run   Validate the policy and print the planned API calls and payload.
#               Never calls GitHub and never mutates anything.
#   --apply     Idempotent create-or-update of the protection settings. Runs the
#               token/repository preflight before the first mutation.
#   --verify    Compare the live settings with the desired policy and exit 1 on
#               the first differing fields (used by the drift workflow).
#   --expected  Print the desired payload as JSON.
#   --list-paths  Print the trace path ids used by the branch-coverage gate.
#
# Exit codes: 0 success, 1 validation/preflight/API/drift failure.
#
# Environment:
#   GH_REPO / GITHUB_REPOSITORY       default repository
#   GH_TOKEN                          token used by gh (requires repository admin)
#   GH_CLI                            gh binary override (tests)
#   JQ_CLI                            jq binary override (tests)
#   BRANCH_PROTECTION_WORKFLOWS_DIR   workflow directory override (tests)
#   BRANCH_PROTECTION_TRACE_FILE      append executed path ids (coverage gate)
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

GH_CLI="${GH_CLI:-gh}"
JQ_CLI="${JQ_CLI:-jq}"
WORKFLOWS_DIR="${BRANCH_PROTECTION_WORKFLOWS_DIR:-$REPO_ROOT/.github/workflows}"
TRACE_FILE="${BRANCH_PROTECTION_TRACE_FILE:-}"

BRANCH="main"
REPO="${GH_REPO:-${GITHUB_REPOSITORY:-}}"
MODE=""
PROTECTION_JSON=""
PROTECTION_STATUS=""

# ---------------------------------------------------------------------------
# Single source of truth for the required status checks.
#
# Every name must match the `name:` of a job in .github/workflows/ci.yml or
# .github/workflows/version-compatibility.yml. validate_policy() fails when a
# name is unknown and when an unconditional workflow job is not listed here, so
# renaming a job cannot silently leave a required check pending forever.
# ---------------------------------------------------------------------------
REQUIRED_CHECKS=(
  "Backend"
  "Frontend"
  "AI Layer"
  "Verify Versions"
  "Vulnerability Scan"
  "Secret Scan"
  "Dependency Scan"
  "Code Quality"
  "Docker Build"
  "End-to-End Tests"
  "API Lint"
  "Observability Checks"
  "CDC Checks"
  "Load Tests"
  "Branch Protection"
)

# Jobs that are intentionally not required: `pact-gate` only runs once a Pact
# Broker is configured (PACT_BROKER_BASE_URL), so requiring it would leave PRs
# pending on repositories without a broker.
ALLOWED_UNREQUIRED_CHECKS=(
  "Contract Verification"
)

# Every executable branch/error path. The node:test suite runs each scenario
# with BRANCH_PROTECTION_TRACE_FILE and fails if any id below is never marked.
TRACE_PATHS=(
  "mode_help"
  "mode_list_paths"
  "mode_expected"
  "mode_dry_run"
  "mode_apply_create"
  "mode_apply_update"
  "mode_verify_match"
  "mode_verify_drift"
  "mode_verify_unprotected"
  "error_unknown_option"
  "error_missing_value"
  "error_unexpected_argument"
  "error_conflicting_modes"
  "error_gh_missing"
  "error_jq_missing"
  "error_repo_unresolved"
  "error_invalid_repo"
  "error_workflows_missing"
  "error_unknown_check"
  "error_unlisted_check"
  "error_not_authenticated"
  "error_repo_unreachable"
  "error_missing_scope"
  "error_insufficient_permissions"
  "error_read_failed"
  "error_apply_failed"
)

mark() {
  local id="$1" known
  for known in "${TRACE_PATHS[@]}"; do
    if [ "$known" = "$id" ]; then
      if [ -n "$TRACE_FILE" ]; then
        printf '%s\n' "$id" >>"$TRACE_FILE"
      fi
      return 0
    fi
  done
  printf 'apply-branch-protection: internal error: unknown trace path %s\n' "$id" >&2
  exit 70
}

die() {
  local path_id="$1"
  shift
  mark "$path_id"
  printf 'apply-branch-protection: ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: scripts/apply-branch-protection.sh [MODE] [options]

Modes (default: --dry-run):
  --dry-run     Print the planned GitHub API calls and payload; change nothing.
  --apply       Idempotently create or update main branch protection.
  --verify      Fail when the live protection differs from the desired policy.
  --expected    Print the desired payload as JSON.
  --list-paths  Print the branch-coverage trace ids.
  --help        Show this help.

Options:
  --repo owner/repo   Target repository (default: GH_REPO or GITHUB_REPOSITORY).
  --branch name       Protected branch (default: main).
  --workflows-dir dir Workflow directory used for check-name validation.

The token used by gh needs repository admin permission. Classic tokens need the
`repo` scope; fine-grained tokens need Administration: read and write.
EOF
}

set_mode() {
  local requested="$1"
  if [ -n "$MODE" ] && [ "$MODE" != "$requested" ]; then
    die "error_conflicting_modes" \
      "only one of --apply, --dry-run, --verify, --expected, --list-paths or --help may be used."
  fi
  MODE="$requested"
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [ "$#" -gt 0 ]; do
  case "$1" in
    --apply)
      set_mode "apply"
      ;;
    --dry-run)
      set_mode "dry_run"
      ;;
    --verify)
      set_mode "verify"
      ;;
    --expected)
      set_mode "expected"
      ;;
    --list-paths)
      set_mode "list_paths"
      ;;
    --help | -h)
      set_mode "help"
      ;;
    --repo)
      if [ "$#" -lt 2 ]; then
        die "error_missing_value" "--repo requires an owner/repo value."
      fi
      REPO="$2"
      shift
      ;;
    --repo=*)
      REPO="${1#*=}"
      ;;
    --branch)
      if [ "$#" -lt 2 ]; then
        die "error_missing_value" "--branch requires a value."
      fi
      BRANCH="$2"
      shift
      ;;
    --branch=*)
      BRANCH="${1#*=}"
      ;;
    --workflows-dir)
      if [ "$#" -lt 2 ]; then
        die "error_missing_value" "--workflows-dir requires a path."
      fi
      WORKFLOWS_DIR="$2"
      shift
      ;;
    --workflows-dir=*)
      WORKFLOWS_DIR="${1#*=}"
      ;;
    -*)
      die "error_unknown_option" "unknown option '$1'. Run --help for usage."
      ;;
    *)
      die "error_unexpected_argument" "unexpected argument '$1'. Run --help for usage."
      ;;
  esac
  shift
done

if [ -z "$MODE" ]; then
  MODE="dry_run"
fi

if [ "$MODE" = "help" ]; then
  usage
  mark "mode_help"
  exit 0
fi

if [ "$MODE" = "list_paths" ]; then
  printf '%s\n' "${TRACE_PATHS[@]}"
  mark "mode_list_paths"
  exit 0
fi

if ! command -v "$JQ_CLI" >/dev/null 2>&1; then
  die "error_jq_missing" "jq is required (looked for '$JQ_CLI')."
fi

render_expected() {
  local checks_json
  checks_json="$(printf '%s\n' "${REQUIRED_CHECKS[@]}" |
    "$JQ_CLI" -R -s -c 'split("\n") | map(select(length > 0))')"
  # shellcheck disable=SC2016 # $checks is a jq variable, not a shell variable.
  "$JQ_CLI" -n --argjson checks "$checks_json" '
    {
      required_status_checks: {
        strict: true,
        contexts: $checks
      },
      enforce_admins: true,
      required_pull_request_reviews: {
        dismiss_stale_reviews: true,
        require_code_owner_reviews: false,
        required_approving_review_count: 1
      },
      required_conversation_resolution: true,
      restrictions: null,
      allow_force_pushes: false,
      allow_deletions: false
    }
  '
}

if [ "$MODE" = "expected" ]; then
  render_expected
  mark "mode_expected"
  exit 0
fi

if ! command -v "$GH_CLI" >/dev/null 2>&1; then
  die "error_gh_missing" "GitHub CLI (gh) is required (looked for '$GH_CLI')."
fi

resolve_repo() {
  if [ -z "$REPO" ]; then
    if ! REPO="$("$GH_CLI" repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)"; then
      die "error_repo_unresolved" \
        "cannot determine the repository; pass --repo owner/repo or set GH_REPO/GITHUB_REPOSITORY."
    fi
  fi
  if [[ ! "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    die "error_invalid_repo" "invalid repository '$REPO'; expected owner/repo."
  fi
}

job_names() {
  local file="$1"
  [ -f "$file" ] || return 0
  grep -E '^    name: .+' "$file" | sed -E 's/^    name:[[:space:]]*//; s/[[:space:]]*$//'
}

is_required_check() {
  local candidate="$1" check
  for check in "${REQUIRED_CHECKS[@]}"; do
    if [ "$check" = "$candidate" ]; then
      return 0
    fi
  done
  return 1
}

is_allowed_unrequired() {
  local candidate="$1" allowed
  for allowed in "${ALLOWED_UNREQUIRED_CHECKS[@]}"; do
    if [ "$allowed" = "$candidate" ]; then
      return 0
    fi
  done
  return 1
}

validate_policy() {
  local ci="$WORKFLOWS_DIR/ci.yml"
  local compatibility="$WORKFLOWS_DIR/version-compatibility.yml"
  local names="" name check

  if [ ! -f "$ci" ] && [ ! -f "$compatibility" ]; then
    die "error_workflows_missing" \
      "no CI workflows found in '$WORKFLOWS_DIR'; cannot validate the required check names."
  fi

  names="$(job_names "$ci"; job_names "$compatibility")"

  for check in "${REQUIRED_CHECKS[@]}"; do
    if ! grep -Fxq -- "$check" <<<"$names"; then
      die "error_unknown_check" \
        "required check '$check' does not match any job name in '$WORKFLOWS_DIR'."
    fi
  done

  while IFS= read -r name; do
    [ -n "$name" ] || continue
    if is_required_check "$name" || is_allowed_unrequired "$name"; then
      continue
    fi
    die "error_unlisted_check" \
      "workflow job '$name' is neither required nor listed in ALLOWED_UNREQUIRED_CHECKS; add it to REQUIRED_CHECKS."
  done <<<"$names"
}

preflight_remote() {
  local response scopes body admin normalized_scopes

  if ! "$GH_CLI" auth status >/dev/null 2>&1; then
    die "error_not_authenticated" "gh is not authenticated; run 'gh auth login' or provide GH_TOKEN."
  fi

  if ! response="$("$GH_CLI" api -i "repos/$REPO" 2>/dev/null)"; then
    die "error_repo_unreachable" \
      "cannot read repos/$REPO; check the repository name and the token."
  fi

  response="$(printf '%s' "$response" | tr -d '\r')"
  scopes="$(printf '%s\n' "$response" |
    awk 'BEGIN { IGNORECASE = 1 } /^x-oauth-scopes:/ { sub(/^[^:]*:[[:space:]]*/, ""); print; exit }')"
  body="$(printf '%s\n' "$response" | awk 'found { print } /^$/ { found = 1 }')"
  if [ -z "$body" ]; then
    body="$response"
  fi
  admin="$(printf '%s' "$body" | "$JQ_CLI" -r '.permissions.admin // false' 2>/dev/null || true)"

  if [ -n "$scopes" ]; then
    normalized_scopes=",${scopes// /},"
    case "$normalized_scopes" in
      *,repo,*) ;;
      *)
        die "error_missing_scope" \
          "classic token scopes ($scopes) lack 'repo'; branch protection requires repository admin."
        ;;
    esac
  fi

  if [ "$admin" != "true" ]; then
    die "error_insufficient_permissions" \
      "the token does not have admin permission on $REPO; branch protection cannot be changed."
  fi
}

fetch_protection() {
  local stderr_file
  stderr_file="$(mktemp)"
  if PROTECTION_JSON="$("$GH_CLI" api "repos/$REPO/branches/$BRANCH/protection" 2>"$stderr_file")"; then
    PROTECTION_STATUS="present"
  elif grep -q 'HTTP 404' "$stderr_file"; then
    PROTECTION_STATUS="missing"
    PROTECTION_JSON=""
  else
    sed 's/^/  /' "$stderr_file" >&2
    rm -f "$stderr_file"
    die "error_read_failed" \
      "cannot read branch protection for $BRANCH in $REPO; the token needs Administration: read."
  fi
  rm -f "$stderr_file"
}

apply_protection() {
  local payload action
  payload="$(render_expected)"
  fetch_protection

  if [ "$PROTECTION_STATUS" = "missing" ]; then
    action="created"
    mark "mode_apply_create"
  else
    action="updated"
    mark "mode_apply_update"
  fi

  if ! printf '%s\n' "$payload" |
    "$GH_CLI" api -X PUT \
      "repos/$REPO/branches/$BRANCH/protection" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      --input - >/dev/null; then
    die "error_apply_failed" \
      "failed to apply branch protection for $BRANCH in $REPO; fix the cause and rerun (the script is idempotent)."
  fi

  printf 'Branch protection for %s in %s %s.\n' "$BRANCH" "$REPO" "$action"
}

verify_protection() {
  local payload diff
  payload="$(render_expected)"
  fetch_protection

  if [ "$PROTECTION_STATUS" = "missing" ]; then
    mark "mode_verify_unprotected"
    printf 'DRIFT %s: branch protection is not configured in %s.\n' "$BRANCH" "$REPO" >&2
    printf 'Run scripts/apply-branch-protection.sh --apply to converge.\n' >&2
    exit 1
  fi

  # shellcheck disable=SC2016 # $actual/$desired/$path are jq variables.
  diff="$("$JQ_CLI" -rn --argjson expected "$payload" --argjson live "$PROTECTION_JSON" '
    ($live | {
      required_status_checks: {
        strict: .required_status_checks.strict,
        contexts: ((.required_status_checks.contexts // []) | sort)
      },
      enforce_admins: .enforce_admins.enabled,
      required_pull_request_reviews: {
        dismiss_stale_reviews: .required_pull_request_reviews.dismiss_stale_reviews,
        require_code_owner_reviews: .required_pull_request_reviews.require_code_owner_reviews,
        required_approving_review_count: .required_pull_request_reviews.required_approving_review_count
      },
      required_conversation_resolution: .required_conversation_resolution.enabled,
      allow_force_pushes: .allow_force_pushes.enabled,
      allow_deletions: .allow_deletions.enabled
    }) as $actual
    | ($expected | .required_status_checks.contexts |= sort) as $desired
    | [ "required_status_checks.strict",
        "required_status_checks.contexts",
        "enforce_admins",
        "required_pull_request_reviews.dismiss_stale_reviews",
        "required_pull_request_reviews.require_code_owner_reviews",
        "required_pull_request_reviews.required_approving_review_count",
        "required_conversation_resolution",
        "allow_force_pushes",
        "allow_deletions" ][]
    | . as $path
    | (reduce ($path | split("."))[] as $key ($desired; .[$key])) as $want
    | (reduce ($path | split("."))[] as $key ($actual; .[$key])) as $got
    | select($want != $got)
    | "DRIFT \($path): expected \($want | tojson), got \($got | tojson)"
  ')"

  if [ -n "$diff" ]; then
    mark "mode_verify_drift"
    printf '%s\n' "$diff" >&2
    printf 'Branch protection drift detected for %s in %s; run scripts/apply-branch-protection.sh --apply to converge.\n' \
      "$BRANCH" "$REPO" >&2
    exit 1
  fi

  mark "mode_verify_match"
  printf 'Branch protection for %s in %s matches the reviewed policy.\n' "$BRANCH" "$REPO"
}

dry_run() {
  local payload
  payload="$(render_expected)"
  printf 'DRY RUN: no GitHub API mutation will be performed.\n'
  printf 'Planned calls for %s:\n' "$REPO"
  printf '  GET %s\n' "repos/$REPO/branches/$BRANCH/protection"
  printf '  PUT %s\n' "repos/$REPO/branches/$BRANCH/protection"
  printf 'Payload:\n%s\n' "$payload"
  mark "mode_dry_run"
}

resolve_repo
validate_policy

case "$MODE" in
  dry_run)
    dry_run
    ;;
  apply)
    preflight_remote
    apply_protection
    ;;
  verify)
    preflight_remote
    verify_protection
    ;;
  *)
    die "error_unknown_option" "unsupported mode '$MODE'."
    ;;
esac
