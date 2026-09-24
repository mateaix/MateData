#!/usr/bin/env bash
# Shared by build/run; source this file, rather than executing it directly.
resolve_java_25() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    MATEDATA_JAVA_BIN="$JAVA_HOME/bin/java"
  else
    MATEDATA_JAVA_BIN="$(command -v java || true)"
  fi
  if [[ -z "$MATEDATA_JAVA_BIN" || ! -x "$MATEDATA_JAVA_BIN" ]]; then
    printf '%s\n' 'Java 25 was not found. Set JAVA_HOME to a JDK 25 installation.' >&2
    return 1
  fi
  local details major
  details="$("$MATEDATA_JAVA_BIN" -XshowSettings:properties -version 2>&1)" || return 1
  major="$(printf '%s\n' "$details" | awk '$1 == "java.specification.version" { print $3 }')"
  if [[ "$major" != '25' ]]; then
    printf 'MateData requires Java 25; selected Java is %s. Set JAVA_HOME explicitly.\n' "$major" >&2
    return 1
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
}
