#!/usr/bin/env bash
# Build (if needed) and run the ChangeRadar CLI. Requires JDK 21+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

java_major() {
  "$1" -version 2>&1 | sed -n 's/.* version "\([0-9][0-9]*\).*/\1/p' | head -1
}

pick_java() {
  local min="$1"
  local candidates=()
  if [[ -n "${JAVA_HOME:-}" ]]; then
    candidates+=("${JAVA_HOME}/bin/java")
  fi
  if command -v java >/dev/null 2>&1; then
    candidates+=("$(command -v java)")
  fi
  if [[ -d "${HOME}/.sdkman/candidates/java" ]]; then
    local dir
    for dir in "${HOME}/.sdkman/candidates/java"/*; do
      [[ -x "${dir}/bin/java" ]] && candidates+=("${dir}/bin/java")
    done
  fi
  local jvm
  for jvm in /usr/lib/jvm/*/bin/java; do
    [[ -x "${jvm}" ]] && candidates+=("${jvm}")
  done

  local bin major
  for bin in "${candidates[@]}"; do
    [[ -x "${bin}" ]] || continue
    major="$(java_major "${bin}" || true)"
    if [[ -n "${major}" && "${major}" -ge "${min}" ]]; then
      echo "${bin}"
      return 0
    fi
  done
  return 1
}

CLI_JAVA="$(pick_java 21)" || {
  echo "changeradar: JDK 21 or newer is required (see docs/development.md)." >&2
  exit 2
}

if [[ -n "${CHANGERADAR_JAR:-}" ]]; then
  exec "${CLI_JAVA}" -jar "${CHANGERADAR_JAR}" "$@"
fi

./gradlew --quiet :changeradar-cli:installDist

export JAVA_HOME="$(cd "$(dirname "${CLI_JAVA}")/.." && pwd)"
export PATH="${JAVA_HOME}/bin:${PATH}"
exec "${ROOT}/changeradar-cli/build/install/changeradar/bin/changeradar" "$@"
