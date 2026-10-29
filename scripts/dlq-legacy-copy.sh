#!/usr/bin/env sh
#
# Issue #123: copy or reprocess messages from the legacy awap.dlq topic.
#
# The tool reads every source partition from the beginning, skips the offsets
# recorded in its checkpoint and flushes the producer before advancing the
# checkpoint. Running it twice therefore copies each message exactly once.
#
# Build the identity jar first:
#   backend/mvnw -B -pl identity -am -DskipTests package
#
set -eu

MAIN_CLASS="com.awap.engine.infrastructure.LegacyDlqCopyCli"
LAUNCHER="${AWAP_BOOT_LAUNCHER:-org.springframework.boot.loader.launch.PropertiesLauncher}"

usage() {
  cat <<'EOF'
Usage: scripts/dlq-legacy-copy.sh [--dry-run] [options]

Options:
  --dry-run                    Report what would be copied, change nothing
  --bootstrap-servers=HOSTS    Kafka bootstrap servers (default: $KAFKA_BOOTSTRAP_SERVERS)
  --source-topic=NAME          Source topic (default: awap.dlq)
  --fallback-topic=NAME        Target when the original topic is unknown
                               (default: awap.legacy.dlq)
  --max-messages=N             Bound for one invocation (default: 10000)
  --poll-timeout-ms=N          Consumer poll timeout (default: 1000)
  --state-file=PATH            Checkpoint file
                               (default: target/legacy-dlq-copy-state.properties)
  --help, -h                   Print this help

Environment:
  AWAP_IDENTITY_JAR            Path to the Spring Boot identity jar
  JAVA_OPTS                    Extra JVM options
  AWAP_BOOT_LAUNCHER           Spring Boot launcher class (default:
                               org.springframework.boot.loader.launch.PropertiesLauncher)

Run --dry-run first, then run the command twice without --dry-run and compare
the destination end offsets. The deprecation window is documented in runbook O7.
EOF
}

case "${1:-}" in
  --help|-h)
    usage
    exit 0
    ;;
esac

find_jar() {
  if [ -n "${AWAP_IDENTITY_JAR:-}" ]; then
    if [ ! -f "$AWAP_IDENTITY_JAR" ]; then
      echo "AWAP_IDENTITY_JAR does not exist: $AWAP_IDENTITY_JAR" >&2
      return 1
    fi
    printf '%s\n' "$AWAP_IDENTITY_JAR"
    return 0
  fi
  for candidate in \
    backend/identity/target/awap-identity-*.jar \
    backend/identity/target/identity-*.jar \
    identity/target/awap-identity-*.jar \
    identity/target/identity-*.jar \
    target/awap-identity-*.jar \
    target/identity-*.jar; do
    case "$candidate" in
      *-sources.jar|*-javadoc.jar|*.original) continue ;;
    esac
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

JAR="$(find_jar)" || {
  echo "Identity jar not found. Build it with:" >&2
  echo "  backend/mvnw -B -pl identity -am -DskipTests package" >&2
  echo "or point AWAP_IDENTITY_JAR at an existing jar." >&2
  exit 1
}

exec java ${JAVA_OPTS:-} -Dloader.main="$MAIN_CLASS" -cp "$JAR" "$LAUNCHER" "$@"
