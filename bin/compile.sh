#!/usr/bin/env bash
#
# CM Insight - compile (a thin wrapper around build.sh).
#
#   ./bin/compile.sh [--help]
#
# Resolves JAVA_HOME exactly like build.sh (accepting /c/tools/jdk17,
# C:/tools/jdk17 or C:\tools\jdk17 and exporting the normalised value) and then
# runs ./build.sh, which compiles the main and test sources, runs the required
# test suite and packages build/cm-insight.jar. No network access.
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 compiled, 1 build failure, 2 usage or toolchain problem.

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./bin/compile.sh [--help]

Compiles CM Insight offline by delegating to ./build.sh (javac --release 17,
-Xlint:all) and running the required test suite.

Environment:
  JAVA_HOME   JDK 17+ home; accepted as /c/tools/jdk17, C:/tools/jdk17 or
              C:\tools\jdk17, and exported normalised to build.sh. Falls back to PATH.

For the full build contract see ./build.sh --help.
USAGE
}

for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage; exit 0 ;;
    *) printf 'ERROR: unknown argument: %s\n' "${arg}" >&2; usage >&2; exit 2 ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
[ -d "${SCRIPT_DIR}" ] || { printf 'ERROR: cannot locate script directory: %s\n' "${SCRIPT_DIR}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)" || { printf 'ERROR: cannot resolve repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

BUILD_SCRIPT="${ROOT}/build.sh"
[ -f "${BUILD_SCRIPT}" ] || { printf 'ERROR: %s is missing\n' "${BUILD_SCRIPT}" >&2; exit 1; }

JH="${JAVA_HOME:-}"
if [ -n "${JH}" ]; then
  case "$(uname -s 2>/dev/null || printf 'unknown')" in
    MINGW*|MSYS*|CYGWIN*)
      case "${JH}" in
        [A-Za-z]:[\\/]*)
          _drive="$(printf '%s' "${JH}" | cut -c1 | tr 'A-Z' 'a-z')"
          _rest="$(printf '%s' "${JH}" | cut -c3- | tr '\\' '/')"
          JH="/${_drive}${_rest}"
          ;;
      esac
      ;;
  esac
  case "${JH}" in */) JH="${JH%/}" ;; esac
  export JAVA_HOME="${JH}"
  printf 'OK: JAVA_HOME=%s\n' "${JH}"
else
  printf 'OK: JAVA_HOME is not set; using javac/jar/java from PATH\n'
fi

exec "${BUILD_SCRIPT}" "$@"
