#!/usr/bin/env bash
#
# Applies and verifies repository governance for issue #180: the label
# taxonomy, milestones, merge policy and their ownership metadata.
#
# The desired state is defined exactly once in
# settings/repository-governance.json. This script never deletes a label unless
# --prune is passed explicitly, and it never deletes a milestone.
#
# Usage:
#   scripts/apply-repo-governance.sh --dry-run [--prune] [--repo owner/repo]
#   scripts/apply-repo-governance.sh --apply   [--prune] [--repo owner/repo]
#   scripts/apply-repo-governance.sh --verify  [--repo owner/repo]
#   scripts/apply-repo-governance.sh --expected
#   scripts/apply-repo-governance.sh --list-paths
#
# Modes:
#   --dry-run    Default. Validate the desired state and print the planned
#                calls. Never calls GitHub and never changes anything.
#   --apply      Idempotently create or update labels, milestones and the merge
#                policy, then apply branch protection through
#                scripts/apply-branch-protection.sh.
#   --verify     Compare the live labels and milestones with the desired state.
#                Missing or drifted items fail the run. Unknown labels and
#                milestones are reported but never deleted.
#   --expected   Print the desired state as JSON.
#   --list-paths Print the trace ids used by the coverage gate.
#
# --prune is only valid with --apply or --dry-run. It deletes live labels that
# are not listed in settings/repository-governance.json. It never deletes a
# desired label and it never deletes a milestone.
#
# Exit codes: 0 success, 1 validation/preflight/API/drift failure.
#
# Environment:
#   GH_REPO / GITHUB_REPOSITORY                  default repository
#   GH_TOKEN                                     token used by gh
#   GH_CLI                                       gh binary override (tests)
#   JQ_CLI                                       jq binary override (tests)
#   REPO_GOVERNANCE_STATE_FILE                   desired-state file override
#   REPO_GOVERNANCE_TRACE_FILE                   append executed path ids
#   REPO_GOVERNANCE_BRANCH_PROTECTION_SCRIPT     branch protection script override
#
# jq programs use $name/$key/$title as jq variables inside single quotes, so
# ShellCheck's SC2016 is disabled for the whole file.
# shellcheck disable=SC2016
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

GH_CLI="${GH_CLI:-gh}"
JQ_CLI="${JQ_CLI:-jq}"
STATE_FILE="${REPO_GOVERNANCE_STATE_FILE:-$REPO_ROOT/settings/repository-governance.json}"
TRACE_FILE="${REPO_GOVERNANCE_TRACE_FILE:-}"
BRANCH_PROTECTION_SCRIPT="${REPO_GOVERNANCE_BRANCH_PROTECTION_SCRIPT:-$SCRIPT_DIR/apply-branch-protection.sh}"

REPO="${GH_REPO:-${GITHUB_REPOSITORY:-}}"
MODE=""
PRUNE=0
REPO_BODY=""
LIVE_LABELS="[]"
LIVE_MILESTONES="[]"

# Every executable branch/error path. The node:test suite runs each scenario
# with REPO_GOVERNANCE_TRACE_FILE and fails if any id below is never marked.
TRACE_PATHS=(
  "mode_help"
  "mode_list_paths"
  "mode_expected"
  "mode_dry_run"
  "mode_dry_run_prune"
  "mode_apply_created"
  "mode_apply_updated"
  "mode_apply_noop"
  "mode_apply_pruned"
  "mode_apply_settings_updated"
  "mode_apply_branch_protection"
  "mode_verify_match"
  "mode_verify_drift"
  "mode_verify_unknown_label"
  "mode_verify_unknown_milestone"
  "warn_duplicate_milestone"
  "error_unknown_option"
  "error_missing_value"
  "error_unexpected_argument"
  "error_conflicting_modes"
  "error_prune_requires_mutation"
  "error_gh_missing"
  "error_jq_missing"
  "error_repo_unresolved"
  "error_invalid_repo"
  "error_state_missing"
  "error_state_invalid_json"
  "error_state_invalid_label"
  "error_state_duplicate_label"
  "error_state_invalid_milestone"
  "error_state_duplicate_milestone"
  "error_state_invalid_merge_policy"
  "error_not_authenticated"
  "error_repo_unreachable"
  "error_missing_scope"
  "error_insufficient_permissions"
  "error_labels_read_failed"
  "error_milestones_read_failed"
  "error_label_apply_failed"
  "error_milestone_apply_failed"
  "error_merge_policy_failed"
  "error_prune_failed"
  "error_branch_protection_missing"
  "error_branch_protection_failed"
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
  printf 'apply-repo-governance: internal error: unknown trace path %s\n' "$id" >&2
  exit 70
}

die() {
  local path_id="$1"
  shift
  mark "$path_id"
  printf 'apply-repo-governance: ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: scripts/apply-repo-governance.sh [MODE] [options]

Modes (default: --dry-run):
  --dry-run     Print the planned GitHub API calls and payload; change nothing.
  --apply       Idempotently create or update labels, milestones and merge policy.
  --verify      Fail when live labels or milestones differ from the desired state.
  --expected    Print the desired state as JSON.
  --list-paths  Print the coverage trace ids.
  --help        Show this help.

Options:
  --prune             With --apply, delete live labels that are not in the
                      desired state. With --dry-run, print the planned deletes.
                      Never deletes a desired label or any milestone.
  --repo owner/repo   Target repository (default: GH_REPO or GITHUB_REPOSITORY).

The desired state lives in settings/repository-governance.json. --verify reports
unknown labels and milestones but never deletes them; use --apply --prune only
after all automation and saved filters have moved to the replacement label.

The token used by gh needs read access for --verify and repository admin for
--apply (classic token scope `repo`; fine-grained tokens need repository
Contents: read, Issues: read/write and Administration: read/write).
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
    --prune)
      PRUNE=1
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

if [ "$PRUNE" -eq 1 ] && [ "$MODE" != "apply" ] && [ "$MODE" != "dry_run" ]; then
  die "error_prune_requires_mutation" "--prune is only valid with --apply or --dry-run."
fi

if ! command -v "$JQ_CLI" >/dev/null 2>&1; then
  die "error_jq_missing" "jq is required (looked for '$JQ_CLI')."
fi

# ---------------------------------------------------------------------------
# Desired-state validation
# ---------------------------------------------------------------------------
validate_state() {
  local label_count unique_label_count milestone_count unique_milestone_count

  if [ ! -f "$STATE_FILE" ]; then
    die "error_state_missing" \
      "desired-state file '$STATE_FILE' does not exist; restore settings/repository-governance.json."
  fi

  if ! "$JQ_CLI" -e . "$STATE_FILE" >/dev/null 2>&1; then
    die "error_state_invalid_json" "'$STATE_FILE' is not valid JSON."
  fi

  if ! "$JQ_CLI" -e '
    (.labels | type == "array" and length > 0)
    and all(.labels[];
      (.name | if type == "string" then length > 0 else false end)
      and (.description | if type == "string" then length > 0 and length <= 100 else false end)
      and (.color | if type == "string" then test("^[0-9a-fA-F]{6}$") else false end)
      and (.owner | if type == "string" then test("^@[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)?$") else false end)
    )
  ' "$STATE_FILE" >/dev/null 2>&1; then
    die "error_state_invalid_label" \
      "every label needs a name, a description of at most 100 characters, a 6-digit color and an @owner."
  fi

  if ! "$JQ_CLI" -e '
    (.milestones | type == "array" and length > 0)
    and all(.milestones[];
      (.title | if type == "string" then length > 0 and length <= 255 else false end)
      and (.description | if type == "string" then length > 0 else false end)
      and (.state | if type == "string" then test("^(open|closed)$") else false end)
      and (.owner | if type == "string" then test("^@[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)?$") else false end)
    )
  ' "$STATE_FILE" >/dev/null 2>&1; then
    die "error_state_invalid_milestone" \
      "every milestone needs a title, a description, an open/closed state and an @owner."
  fi

  if ! "$JQ_CLI" -e '
    (.merge_policy | type == "object")
    and (.merge_policy |
      all(.allow_squash_merge, .allow_merge_commit, .allow_rebase_merge, .delete_branch_on_merge;
        type == "boolean"))
  ' "$STATE_FILE" >/dev/null 2>&1; then
    die "error_state_invalid_merge_policy" \
      "merge_policy must define allow_squash_merge, allow_merge_commit, allow_rebase_merge and delete_branch_on_merge as booleans."
  fi

  label_count="$("$JQ_CLI" -r '[.labels[].name] | length' "$STATE_FILE")"
  unique_label_count="$("$JQ_CLI" -r '[.labels[].name] | unique | length' "$STATE_FILE")"
  if [ "$label_count" != "$unique_label_count" ]; then
    die "error_state_duplicate_label" "the desired state contains duplicate label names."
  fi

  milestone_count="$("$JQ_CLI" -r '[.milestones[].title] | length' "$STATE_FILE")"
  unique_milestone_count="$("$JQ_CLI" -r '[.milestones[].title] | unique | length' "$STATE_FILE")"
  if [ "$milestone_count" != "$unique_milestone_count" ]; then
    die "error_state_duplicate_milestone" "the desired state contains duplicate milestone titles."
  fi
}

validate_state

if [ "$MODE" = "expected" ]; then
  "$JQ_CLI" . "$STATE_FILE"
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

preflight_remote() {
  local requested_mode="$1" response scopes body admin normalized_scopes

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

  if ! "$JQ_CLI" -e . <<<"$body" >/dev/null 2>&1; then
    die "error_repo_unreachable" "the repos/$REPO response is not valid JSON."
  fi

  REPO_BODY="$body"

  if [ -n "$scopes" ]; then
    normalized_scopes=",${scopes// /},"
    case "$normalized_scopes" in
      *,repo,*) ;;
      *)
        die "error_missing_scope" \
          "classic token scopes ($scopes) lack 'repo'; repository governance requires repository access."
        ;;
    esac
  fi

  if [ "$requested_mode" = "apply" ]; then
    admin="$("$JQ_CLI" -r '.permissions.admin // false' <<<"$REPO_BODY")"
    if [ "$admin" != "true" ]; then
      die "error_insufficient_permissions" \
        "the token does not have admin permission on $REPO; --apply changes repository settings and branch protection."
    fi
  fi
}

# ---------------------------------------------------------------------------
# Live-state readers
# ---------------------------------------------------------------------------
fetch_collection() {
  local endpoint="$1" error_id="$2" raw err normalized

  raw="$(mktemp)"
  err="$(mktemp)"
  if ! "$GH_CLI" api --paginate "$endpoint" >"$raw" 2>"$err"; then
    sed 's/^/  /' "$err" >&2
    rm -f "$raw" "$err"
    die "$error_id" "cannot read $endpoint in $REPO; check the token permissions."
  fi

  if ! normalized="$("$JQ_CLI" -s 'add // []' <"$raw" 2>"$err")"; then
    sed 's/^/  /' "$err" >&2
    rm -f "$raw" "$err"
    die "$error_id" "the response from $endpoint is not a JSON array."
  fi

  rm -f "$raw" "$err"
  printf '%s\n' "$normalized"
}

fetch_live_state() {
  LIVE_LABELS="$(fetch_collection "repos/$REPO/labels?per_page=100" "error_labels_read_failed")"
  LIVE_MILESTONES="$(fetch_collection "repos/$REPO/milestones?state=all&per_page=100" "error_milestones_read_failed")"
}

encode_label_name() {
  "$JQ_CLI" -rn --arg name "$1" '$name | @uri'
}

# ---------------------------------------------------------------------------
# Apply
# ---------------------------------------------------------------------------
apply_merge_policy() {
  local key want got changed=0
  local payload_args=()

  for key in allow_squash_merge allow_merge_commit allow_rebase_merge delete_branch_on_merge; do
    want="$("$JQ_CLI" -r --arg key "$key" '.merge_policy[$key]' "$STATE_FILE")"
    got="$("$JQ_CLI" -r --arg key "$key" '.[$key] // false' <<<"$REPO_BODY")"
    if [ "$want" != "$got" ]; then
      changed=1
    fi
    payload_args+=(-F "$key=$want")
  done

  if [ "$changed" -eq 0 ]; then
    return 0
  fi

  if ! "$GH_CLI" api -X PATCH "repos/$REPO" "${payload_args[@]}" >/dev/null; then
    die "error_merge_policy_failed" \
      "failed to update the merge policy for $REPO; fix the token permissions and rerun."
  fi

  printf '  updated merge policy\n'
  UPDATED=$((UPDATED + 1))
  mark "mode_apply_settings_updated"
}

apply_labels() {
  local label_json name description color live_count live_color live_description encoded

  while IFS= read -r label_json; do
    name="$("$JQ_CLI" -r '.name' <<<"$label_json")"
    description="$("$JQ_CLI" -r '.description' <<<"$label_json")"
    color="$("$JQ_CLI" -r '.color' <<<"$label_json")"
    live_count="$("$JQ_CLI" -r --arg name "$name" '[.[] | select(.name == $name)] | length' <<<"$LIVE_LABELS")"

    if [ "$live_count" -eq 0 ]; then
      if ! "$GH_CLI" api -X POST "repos/$REPO/labels" \
        -f name="$name" -f color="$color" -f description="$description" >/dev/null; then
        die "error_label_apply_failed" "failed to create label '$name' in $REPO."
      fi
      printf '  created label: %s\n' "$name"
      CREATED=$((CREATED + 1))
      continue
    fi

    live_color="$("$JQ_CLI" -r --arg name "$name" '[.[] | select(.name == $name)][0].color // ""' <<<"$LIVE_LABELS")"
    live_description="$("$JQ_CLI" -r --arg name "$name" '[.[] | select(.name == $name)][0].description // ""' <<<"$LIVE_LABELS")"

    if [ "$live_color" = "$color" ] && [ "$live_description" = "$description" ]; then
      continue
    fi

    encoded="$(encode_label_name "$name")"
    if ! "$GH_CLI" api -X PATCH "repos/$REPO/labels/$encoded" \
      -f color="$color" -f description="$description" >/dev/null; then
      die "error_label_apply_failed" "failed to update label '$name' in $REPO."
    fi
    printf '  updated label: %s\n' "$name"
    UPDATED=$((UPDATED + 1))
  done < <("$JQ_CLI" -c '.labels[]' "$STATE_FILE")
}

apply_milestones() {
  local milestone_json title description state match_count number live_description live_state

  while IFS= read -r milestone_json; do
    title="$("$JQ_CLI" -r '.title' <<<"$milestone_json")"
    description="$("$JQ_CLI" -r '.description' <<<"$milestone_json")"
    state="$("$JQ_CLI" -r '.state' <<<"$milestone_json")"
    match_count="$("$JQ_CLI" -r --arg title "$title" '[.[] | select(.title == $title)] | length' <<<"$LIVE_MILESTONES")"

    if [ "$match_count" -eq 0 ]; then
      if ! "$GH_CLI" api -X POST "repos/$REPO/milestones" \
        -f title="$title" -f description="$description" -f state="$state" >/dev/null; then
        die "error_milestone_apply_failed" "failed to create milestone '$title' in $REPO."
      fi
      printf '  created milestone: %s\n' "$title"
      CREATED=$((CREATED + 1))
      continue
    fi

    if [ "$match_count" -gt 1 ]; then
      printf 'WARNING duplicate milestone %s: %s entries exist; updating the first and not creating another.\n' \
        "'$title'" "$match_count" >&2
      mark "warn_duplicate_milestone"
    fi

    number="$("$JQ_CLI" -r --arg title "$title" '[.[] | select(.title == $title)] | sort_by(.number) | .[0].number' <<<"$LIVE_MILESTONES")"
    live_description="$("$JQ_CLI" -r --argjson number "$number" '[.[] | select(.number == $number)][0].description // ""' <<<"$LIVE_MILESTONES")"
    live_state="$("$JQ_CLI" -r --argjson number "$number" '[.[] | select(.number == $number)][0].state // ""' <<<"$LIVE_MILESTONES")"

    if [ "$live_description" = "$description" ] && [ "$live_state" = "$state" ]; then
      continue
    fi

    if ! "$GH_CLI" api -X PATCH "repos/$REPO/milestones/$number" \
      -f description="$description" -f state="$state" >/dev/null; then
      die "error_milestone_apply_failed" "failed to update milestone '$title' in $REPO."
    fi
    printf '  updated milestone: %s\n' "$title"
    UPDATED=$((UPDATED + 1))
  done < <("$JQ_CLI" -c '.milestones[]' "$STATE_FILE")
}

prune_unknown_labels() {
  local live_name encoded

  while IFS= read -r live_name; do
    [ -n "$live_name" ] || continue
    if "$JQ_CLI" -e --arg name "$live_name" 'any(.labels[]; .name == $name)' "$STATE_FILE" >/dev/null; then
      continue
    fi

    encoded="$(encode_label_name "$live_name")"
    if ! "$GH_CLI" api -X DELETE "repos/$REPO/labels/$encoded" >/dev/null; then
      die "error_prune_failed" "failed to prune label '$live_name' from $REPO."
    fi
    printf '  pruned label: %s\n' "$live_name"
    PRUNED=$((PRUNED + 1))
  done < <("$JQ_CLI" -r '.[].name' <<<"$LIVE_LABELS")
}

apply_branch_protection() {
  if [ ! -x "$BRANCH_PROTECTION_SCRIPT" ]; then
    die "error_branch_protection_missing" \
      "branch protection script '$BRANCH_PROTECTION_SCRIPT' is missing or not executable."
  fi

  if ! "$BRANCH_PROTECTION_SCRIPT" --apply --repo "$REPO"; then
    die "error_branch_protection_failed" \
      "failed to apply branch protection for $REPO; fix the cause and rerun."
  fi
  mark "mode_apply_branch_protection"
}

apply_governance() {
  CREATED=0
  UPDATED=0
  PRUNED=0

  fetch_live_state
  apply_merge_policy
  apply_labels
  apply_milestones

  if [ "$PRUNE" -eq 1 ]; then
    prune_unknown_labels
  fi

  apply_branch_protection

  if [ "$CREATED" -gt 0 ]; then
    mark "mode_apply_created"
  fi
  if [ "$UPDATED" -gt 0 ]; then
    mark "mode_apply_updated"
  fi
  if [ "$PRUNED" -gt 0 ]; then
    mark "mode_apply_pruned"
  fi
  if [ "$CREATED" -eq 0 ] && [ "$UPDATED" -eq 0 ] && [ "$PRUNED" -eq 0 ]; then
    mark "mode_apply_noop"
  fi

  printf 'Repository governance applied to %s: %s created, %s updated, %s pruned.\n' \
    "$REPO" "$CREATED" "$UPDATED" "$PRUNED"
}

# ---------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------
verify_governance() {
  local drift=0 unknown_label=0 unknown_milestone=0
  local label_json name description color live_count live_color live_description
  local milestone_json title state match_count number live_description live_state
  local live_name key want got

  fetch_live_state

  while IFS= read -r label_json; do
    name="$("$JQ_CLI" -r '.name' <<<"$label_json")"
    description="$("$JQ_CLI" -r '.description' <<<"$label_json")"
    color="$("$JQ_CLI" -r '.color' <<<"$label_json")"
    live_count="$("$JQ_CLI" -r --arg name "$name" '[.[] | select(.name == $name)] | length' <<<"$LIVE_LABELS")"

    if [ "$live_count" -eq 0 ]; then
      printf "DRIFT label '%s' is missing; run scripts/apply-repo-governance.sh --apply.\n" "$name" >&2
      drift=$((drift + 1))
      continue
    fi

    live_color="$("$JQ_CLI" -r --arg name "$name" '[.[] | select(.name == $name)][0].color // ""' <<<"$LIVE_LABELS")"
    live_description="$("$JQ_CLI" -r --arg name "$name" '[.[] | select(.name == $name)][0].description // ""' <<<"$LIVE_LABELS")"

    if [ "$live_color" != "$color" ]; then
      printf "DRIFT label '%s' color: expected %s, got %s.\n" "$name" "$color" "$live_color" >&2
      drift=$((drift + 1))
    fi
    if [ "$live_description" != "$description" ]; then
      printf "DRIFT label '%s' description: expected \"%s\", got \"%s\".\n" \
        "$name" "$description" "$live_description" >&2
      drift=$((drift + 1))
    fi
  done < <("$JQ_CLI" -c '.labels[]' "$STATE_FILE")

  while IFS= read -r milestone_json; do
    title="$("$JQ_CLI" -r '.title' <<<"$milestone_json")"
    description="$("$JQ_CLI" -r '.description' <<<"$milestone_json")"
    state="$("$JQ_CLI" -r '.state' <<<"$milestone_json")"
    match_count="$("$JQ_CLI" -r --arg title "$title" '[.[] | select(.title == $title)] | length' <<<"$LIVE_MILESTONES")"

    if [ "$match_count" -eq 0 ]; then
      printf "DRIFT milestone '%s' is missing; run scripts/apply-repo-governance.sh --apply.\n" "$title" >&2
      drift=$((drift + 1))
      continue
    fi

    if [ "$match_count" -gt 1 ]; then
      printf "DRIFT milestone '%s' has %s entries; keep one and close or delete the extras manually.\n" \
        "$title" "$match_count" >&2
      drift=$((drift + 1))
    fi

    number="$("$JQ_CLI" -r --arg title "$title" '[.[] | select(.title == $title)] | sort_by(.number) | .[0].number' <<<"$LIVE_MILESTONES")"
    live_description="$("$JQ_CLI" -r --argjson number "$number" '[.[] | select(.number == $number)][0].description // ""' <<<"$LIVE_MILESTONES")"
    live_state="$("$JQ_CLI" -r --argjson number "$number" '[.[] | select(.number == $number)][0].state // ""' <<<"$LIVE_MILESTONES")"

    if [ "$live_description" != "$description" ]; then
      printf "DRIFT milestone '%s' description: expected \"%s\", got \"%s\".\n" \
        "$title" "$description" "$live_description" >&2
      drift=$((drift + 1))
    fi
    if [ "$live_state" != "$state" ]; then
      printf "DRIFT milestone '%s' state: expected %s, got %s.\n" "$title" "$state" "$live_state" >&2
      drift=$((drift + 1))
    fi
  done < <("$JQ_CLI" -c '.milestones[]' "$STATE_FILE")

  while IFS= read -r live_name; do
    [ -n "$live_name" ] || continue
    if ! "$JQ_CLI" -e --arg name "$live_name" 'any(.labels[]; .name == $name)' "$STATE_FILE" >/dev/null; then
      printf "UNKNOWN label '%s' is present but not managed; keep it, or run --apply --prune after updating automation.\n" \
        "$live_name" >&2
      unknown_label=1
    fi
  done < <("$JQ_CLI" -r '.[].name' <<<"$LIVE_LABELS")

  while IFS= read -r live_name; do
    [ -n "$live_name" ] || continue
    if ! "$JQ_CLI" -e --arg title "$live_name" 'any(.milestones[]; .title == $title)' "$STATE_FILE" >/dev/null; then
      printf "UNKNOWN milestone '%s' is present but not managed; close or delete it manually when no open issues reference it.\n" \
        "$live_name" >&2
      unknown_milestone=1
    fi
  done < <("$JQ_CLI" -r '.[].title' <<<"$LIVE_MILESTONES")

  for key in allow_squash_merge allow_merge_commit allow_rebase_merge delete_branch_on_merge; do
    want="$("$JQ_CLI" -r --arg key "$key" '.merge_policy[$key]' "$STATE_FILE")"
    got="$("$JQ_CLI" -r --arg key "$key" '.[$key] // false' <<<"$REPO_BODY")"
    if [ "$want" != "$got" ]; then
      printf 'DRIFT merge_policy.%s: expected %s, got %s.\n' "$key" "$want" "$got" >&2
      drift=$((drift + 1))
    fi
  done

  if [ "$unknown_label" -eq 1 ]; then
    mark "mode_verify_unknown_label"
  fi
  if [ "$unknown_milestone" -eq 1 ]; then
    mark "mode_verify_unknown_milestone"
  fi

  if [ "$drift" -gt 0 ]; then
    mark "mode_verify_drift"
    printf 'Repository governance drift detected for %s; run scripts/apply-repo-governance.sh --apply to converge.\n' \
      "$REPO" >&2
    exit 1
  fi

  mark "mode_verify_match"
  printf 'Repository governance in %s matches the reviewed labels, milestones and merge policy.\n' "$REPO"
}

# ---------------------------------------------------------------------------
# Dry run
# ---------------------------------------------------------------------------
dry_run() {
  printf 'DRY RUN: no GitHub API mutation will be performed.\n'
  printf 'Desired state: %s\n' "$STATE_FILE"
  printf 'Planned calls for %s:\n' "$REPO"
  printf '  PATCH repos/%s (merge policy)\n' "$REPO"
  # shellcheck disable=SC2016 # jq variables, not shell variables.
  printf '  POST repos/%s/labels when missing: %s\n' \
    "$REPO" "$("$JQ_CLI" -r '[.labels[].name] | join(", ")' "$STATE_FILE")"
  # shellcheck disable=SC2016 # jq variables, not shell variables.
  printf '  PATCH repos/%s/labels/{name} when color or description drift: %s\n' \
    "$REPO" "$("$JQ_CLI" -r '[.labels[].name] | join(", ")' "$STATE_FILE")"
  # shellcheck disable=SC2016 # jq variables, not shell variables.
  printf '  POST repos/%s/milestones when missing: %s\n' \
    "$REPO" "$("$JQ_CLI" -r '[.milestones[].title] | join(", ")' "$STATE_FILE")"
  # shellcheck disable=SC2016 # jq variables, not shell variables.
  printf '  PATCH repos/%s/milestones/{number} when description or state drift: %s\n' \
    "$REPO" "$("$JQ_CLI" -r '[.milestones[].title] | join(", ")' "$STATE_FILE")"
  if [ "$PRUNE" -eq 1 ]; then
    printf '  DELETE repos/%s/labels/{name} for live labels not listed in the desired state\n' "$REPO"
  fi
  printf '  %s --apply --repo %s\n' "$BRANCH_PROTECTION_SCRIPT" "$REPO"
  printf 'Desired payload:\n'
  "$JQ_CLI" . "$STATE_FILE"
  mark "mode_dry_run"
  if [ "$PRUNE" -eq 1 ]; then
    mark "mode_dry_run_prune"
  fi
}

resolve_repo

case "$MODE" in
  dry_run)
    dry_run
    ;;
  apply)
    preflight_remote "apply"
    apply_governance
    ;;
  verify)
    preflight_remote "verify"
    verify_governance
    ;;
  *)
    die "error_unknown_option" "unsupported mode '$MODE'."
    ;;
esac
