#!/bin/bash
#
# Integration tests for the SysML v2 LSP server.
#
# Requirements: JDK 21+, Node.js, built sysml-lsp-server.jar
#
# Usage: ./test/test-lsp.sh [path-to-jar] [path-to-library]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
JAR="${1:-${REPO_DIR}/sysml-lsp-server.jar}"
LIB="${2:-${REPO_DIR}/build/library-staging/sysml.library}"

if [ ! -f "$JAR" ]; then
  echo "Error: JAR not found: $JAR"
  echo "Run build.sh first."
  exit 1
fi

if [ ! -d "$LIB" ]; then
  echo "Error: Standard library not found: $LIB"
  echo "Run build.sh first."
  exit 1
fi

JAVA="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA" ]; then
  JAVA="java"
fi

echo "=== SysML v2 LSP Server Tests (external library) ==="
echo "JAR:     $JAR"
echo "Library: $LIB"
echo "Java:    $($JAVA -version 2>&1 | head -1)"
echo ""

node "${SCRIPT_DIR}/test-lsp.mjs" "$JAVA" "$JAR" "$LIB" "${SCRIPT_DIR}/fixtures"

echo ""
echo "=== SysML v2 LSP Server Tests (embedded library) ==="
echo ""

node "${SCRIPT_DIR}/test-lsp.mjs" "$JAVA" "$JAR" "" "${SCRIPT_DIR}/fixtures"
