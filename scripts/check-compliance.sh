#!/usr/bin/env sh
#
# Compliance inventory enforcement (issue #98, "compliance" item).
#
# Ensures every file referenced by docs/compliance-inventory.md actually exists,
# so the SOC 2 / ISO 27001 / GDPR-KVKK control map cannot drift from the code.
#
# Usage:
#   scripts/check-compliance.sh
#
# Exits 0 when every reference resolves, 1 when a referenced file is missing.
set -eu

cd "$(dirname "$0")/.."

INVENTORY=docs/compliance-inventory.md
MISSING=0

# Markdown links are written relative to docs/ (e.g. `](../infra/...`).
for ref in $(grep -oE '\]\(\.\./[^)#]+' "$INVENTORY" | sed -E 's/^\]\(//'); do
  resolved="docs/${ref}"
  if [ ! -e "$resolved" ]; then
    echo "check-compliance: FAIL referenced file missing: $resolved" >&2
    MISSING=1
  fi
done

if [ "$MISSING" -ne 0 ]; then
  echo "check-compliance: FAILED" >&2
  exit 1
fi

echo "check-compliance: OK all compliance inventory references resolve"
