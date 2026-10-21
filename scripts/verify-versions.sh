#!/usr/bin/env bash
# Verifies that the fixed language/platform versions match the compatibility
# allowlist from COMPATIBILITY.md. This is the hard gate that rejects PRs
# introducing incompatible versions.
#
# Run by: .github/workflows/version-compatibility.yml
set -euo pipefail

FAILED=0

fail() {
  printf '❌ %s\n' "$1" >&2
  FAILED=1
}

ok() {
  printf '✅ %s\n' "$1"
}

# --- Backend: Java 25 + Spring Boot 4.1.1 ---
POM="backend/pom.xml"

JAVA_VERSION="$(sed -n 's/.*<java.version>\(.*\)<\/java.version>.*/\1/p' "$POM" | head -n1)"
if [ "$JAVA_VERSION" = "25" ]; then
  ok "Java 25 (java.version)"
else
  fail "backend/pom.xml java.version must be 25 (got '${JAVA_VERSION:-<missing>}')"
fi

COMPILER_RELEASE="$(sed -n 's/.*<maven.compiler.release>\(.*\)<\/maven.compiler.release>.*/\1/p' "$POM" | head -n1)"
if [ "$COMPILER_RELEASE" = "25" ]; then
  ok "Java --release 25"
else
  fail "backend/pom.xml maven.compiler.release must be 25 (got '${COMPILER_RELEASE:-<missing>}')"
fi

SPRING_BOOT="$(sed -n '/<parent>/,/<\/parent>/p' "$POM" | sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' | head -n1)"
if [ "$SPRING_BOOT" = "4.1.1" ]; then
  ok "Spring Boot 4.1.1"
else
  fail "backend/pom.xml parent (spring-boot-starter-parent) version must be 4.1.1 (got '${SPRING_BOOT:-<missing>}')"
fi

# --- Frontend: TypeScript 7.0.2 + Node.js 24 ---
PKG="frontend/package.json"

if grep -q '"typescript": "7.0.2"' "$PKG"; then
  ok "TypeScript 7.0.2"
else
  fail "frontend/package.json must pin typescript to 7.0.2"
fi

if [ -f frontend/.nvmrc ]; then
  NODE="$(tr -d '[:space:]' < frontend/.nvmrc)"
  if [ "$NODE" = "24" ]; then
    ok "Node.js 24 (.nvmrc)"
  else
    fail "frontend/.nvmrc must be 24 (got '$NODE')"
  fi
else
  fail "frontend/.nvmrc is missing; it must pin Node.js 24"
fi

# --- AI Layer: Python 3.15.0 ---
PY="ai-layer/pyproject.toml"

if grep -q 'requires-python = ">=3.15.0,<3.16"' "$PY"; then
  ok "Python 3.15.0 (pyproject.toml)"
else
  fail "ai-layer/pyproject.toml requires-python must be >=3.15.0,<3.16"
fi

if [ -f ai-layer/.python-version ]; then
  if grep -q '^3\.15\.0$' ai-layer/.python-version; then
    ok "Python 3.15.0 (.python-version)"
  else
    fail "ai-layer/.python-version must be 3.15.0"
  fi
else
  fail "ai-layer/.python-version is missing; it must pin 3.15.0"
fi

if [ "$FAILED" -ne 0 ]; then
  printf '\nVersion compatibility check FAILED.\n' >&2
  printf 'Update COMPATIBILITY.md, CLAUDE.md and the offending file together in the same PR.\n' >&2
  exit 1
fi

printf '\nAll fixed versions are within the allowlist.\n'
