#!/usr/bin/env bash
set -euo pipefail
MATEDATA_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
printf 'Use two separate terminals with JAVA_HOME pointing to JDK 25 and Node 26.10.0 on PATH.\n\n'
printf 'Terminal 1 — backend (working directory must remain the repository root):\n  cd %q\n' "$MATEDATA_ROOT"
printf '  ./mvnw -pl matedata-server -am install -DskipTests\n'
printf '  ./mvnw -pl matedata-server spring-boot:run -Dspring-boot.run.workingDirectory=%q\n\n' "$MATEDATA_ROOT"
printf 'Terminal 2 — frontend:\n  cd %q\n  npm ci\n  npm run dev -- --host 127.0.0.1\n\n' "$MATEDATA_ROOT/matedata-ui"
printf 'Open http://localhost:5173/. Vite proxies /api to 127.0.0.1:8090. Stop each process with Ctrl+C in its terminal.\n'
