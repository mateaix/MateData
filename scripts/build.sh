#!/usr/bin/env bash
set -euo pipefail
MATEDATA_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MATEDATA_ROOT"
source "$MATEDATA_ROOT/scripts/toolchain.sh"
resolve_java_25
MATEDATA_JAVAC_BIN="$(dirname "$MATEDATA_JAVA_BIN")/javac"
if [[ ! -x "$MATEDATA_JAVAC_BIN" || "$("$MATEDATA_JAVAC_BIN" -version 2>&1)" != 'javac 25'* ]]; then
  printf '%s\n' 'Building requires a full JDK 25. Set JAVA_HOME to its installation directory.' >&2
  exit 1
fi
if ! command -v node >/dev/null 2>&1 || [[ "$(node --version)" != 'v26.10.0' ]]; then
  printf '%s\n' 'Building requires Node.js 26.10.0 (see .nvmrc). Select that runtime before running this script.' >&2
  exit 1
fi
(
  cd "$MATEDATA_ROOT/matedata-ui"
  npm ci
  npm test
  npm run build
)
# Clean prevents obsolete hashed UI assets from surviving incremental packaging.
./mvnw --batch-mode --no-transfer-progress clean verify "$@"
printf '\nBuilt executable application: %s\n' "$MATEDATA_ROOT/matedata-server/target/matedata-server-0.1.0-SNAPSHOT.jar"
