#!/usr/bin/env bash
# Fails when the resolved Jackson BOMs fall below the patched floor that the backend/pom.xml
# override pins for CVE-2026-89407 / 89425 / 68497 / 91776 / 91777 (issue #136).
set -euo pipefail

FLOOR_2="${JACKSON2_FLOOR:-2.21.7}"
FLOOR_3="${JACKSON3_FLOOR:-3.1.7}"
BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

(
  cd "$BACKEND_DIR"
  mvn -q -B -pl identity -am dependency:list \
    -DincludeGroupIds=com.fasterxml.jackson.core,tools.jackson.core \
    -DoutputFile="$OUT" >/dev/null
)

version_for() {
  local group="$1" artifact="$2"
  grep -E "^[[:space:]]*${group}:${artifact}:jar:" "$OUT" | head -1 | awk -F: '{print $4}'
}

at_least() {
  local actual="$1" floor="$2"
  [ "$(printf '%s\n%s\n' "$floor" "$actual" | sort -V | tail -1)" = "$actual" ]
}

jackson2="$(version_for com.fasterxml.jackson.core jackson-databind)"
jackson3="$(version_for tools.jackson.core jackson-databind)"

if [ -z "$jackson2" ]; then
  echo "FAIL: com.fasterxml.jackson.core:jackson-databind is not on the resolved classpath." >&2
  exit 1
fi
if ! at_least "$jackson2" "$FLOOR_2"; then
  echo "FAIL: Jackson 2 databind $jackson2 is below the patched floor $FLOOR_2." >&2
  echo "      Keep the jackson-2-bom.version override until Spring Boot manages >= $FLOOR_2." >&2
  exit 1
fi
if [ -n "$jackson3" ] && ! at_least "$jackson3" "$FLOOR_3"; then
  echo "FAIL: Jackson 3 databind $jackson3 is below the patched floor $FLOOR_3." >&2
  echo "      Keep the jackson-bom.version override until Spring Boot manages >= $FLOOR_3." >&2
  exit 1
fi

echo "Jackson 2 databind: $jackson2 (floor $FLOOR_2)"
echo "Jackson 3 databind: ${jackson3:-not on classpath} (floor $FLOOR_3)"
echo "OK: Jackson BOM override is at or above the patched floor."
