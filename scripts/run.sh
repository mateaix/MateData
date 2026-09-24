#!/usr/bin/env bash
set -euo pipefail
MATEDATA_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MATEDATA_ROOT"
source "$MATEDATA_ROOT/scripts/toolchain.sh"
resolve_java_25
MATEDATA_JAR_PATH="$MATEDATA_ROOT/matedata-server/target/matedata-server-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$MATEDATA_JAR_PATH" ]]; then
  printf '%s\n' 'Application JAR not found. Run ./scripts/build.sh first.' >&2
  exit 1
fi
# Always run at repository root: the default database and encryption key live in ./data.
# JAVA_TOOL_OPTIONS may be used for JVM options; arguments here are Spring application options.
exec "$MATEDATA_JAVA_BIN" -jar "$MATEDATA_JAR_PATH" "$@"
