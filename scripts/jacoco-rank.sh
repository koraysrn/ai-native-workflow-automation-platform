#!/usr/bin/env sh
#
# Ranks classes by JaCoCo line coverage (ascending) so the lowest-covered classes
# can be targeted for tests (issue #96).
#
# Usage:
#   scripts/jacoco-rank.sh [path/to/jacoco.csv]
#
# Defaults to backend/identity/target/site/jacoco/jacoco.csv, produced by:
#   cd backend && ./mvnw -pl identity verify
set -eu

CSV="${1:-backend/identity/target/site/jacoco/jacoco.csv}"

if [ ! -f "$CSV" ]; then
  echo "jacoco-rank: $CSV not found; run 'mvn -pl identity verify' first" >&2
  exit 1
fi

awk -F',' '
  NR == 1 { next }
  {
    missed = $8;
    covered = $9;
    total = missed + covered;
    pct = total == 0 ? 100.0 : (covered / total) * 100.0;
    printf "%6.1f%%  %6d/%-6d  %s\n", pct, covered, total, $3;
  }
' "$CSV" | sort -n
