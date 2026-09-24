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
# A running Boot JAR lazily reads nested classes. Rebuilding target/ must not replace its bytes.
mkdir -p "$MATEDATA_ROOT/.local/runtime"
MATEDATA_RUNTIME_DIR="$(mktemp -d "$MATEDATA_ROOT/.local/runtime/run.XXXXXX")"
cleanup_runtime() {
  rm -f -- "$MATEDATA_RUNTIME_DIR/application.jar"
  rmdir -- "$MATEDATA_RUNTIME_DIR"
}
trap cleanup_runtime EXIT
cp "$MATEDATA_JAR_PATH" "$MATEDATA_RUNTIME_DIR/application.jar"
# Keep the repository as cwd so metadata and keys always resolve to ./data.
"$MATEDATA_JAVA_BIN" -jar "$MATEDATA_RUNTIME_DIR/application.jar" "$@" &
MATEDATA_APPLICATION_PID=$!
MATEDATA_SIGNAL_EXIT=0
forward_signal() {
  MATEDATA_SIGNAL_EXIT="$1"
  kill -TERM "$MATEDATA_APPLICATION_PID" 2>/dev/null || true
}
trap 'forward_signal 130' INT
trap 'forward_signal 143' TERM
set +e
wait "$MATEDATA_APPLICATION_PID"
MATEDATA_APPLICATION_EXIT=$?
# A signal can interrupt wait before Java has completed its graceful shutdown.
while kill -0 "$MATEDATA_APPLICATION_PID" 2>/dev/null; do
  wait "$MATEDATA_APPLICATION_PID"
  MATEDATA_APPLICATION_EXIT=$?
done
if [[ "$MATEDATA_SIGNAL_EXIT" -ne 0 ]]; then
  exit "$MATEDATA_SIGNAL_EXIT"
fi
exit "$MATEDATA_APPLICATION_EXIT"
