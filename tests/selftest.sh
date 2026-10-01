#!/usr/bin/env bash
#
# CM Insight - offline self-test entry point.
#
#   ./tests/selftest.sh [--help]
#
# Runs ./build.sh, which compiles src/main/java and src/test/java and then runs
# the required dependency-free suite com.mraibo.cminsight.test.SelfTest. The
# suite must never be skipped: a missing or failing suite makes this script exit
# non-zero. This wrapper exists so CI and developers have one stable, offline
# command that enforces the whole pipeline (compile + suite + package).
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 all tests passed, non-zero when the build or the suite fails.

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./tests/selftest.sh [--help]

Offline verification of CM Insight:
  1. resolves JAVA_HOME (accepting /c/tools/jdk17, C:/tools/jdk17, C:\tools\jdk17)
     or falls back to PATH
  2. runs ./build.sh: javac --release 17 for main and test sources, copies
     resources, runs com.mraibo.cminsight.test.SelfTest, packages the jar
  3. fails non-zero if anything - including the test suite - fails

No network access is used at any point.
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
  printf 'WARN: JAVA_HOME is not set; using javac/jar/java from PATH\n' >&2
fi

printf 'OK: running the full offline build and test suite via %s\n' "${BUILD_SCRIPT}"
"${BUILD_SCRIPT}"

TEST_CLASS="${ROOT}/build/test-classes/com/mraibo/cminsight/test/SelfTest.class"
if [ ! -f "${TEST_CLASS}" ]; then
  printf 'ERROR: the required test suite class is missing (%s); the suite must run as part of the build\n' "${TEST_CLASS}" >&2
  exit 1
fi

printf 'OK: selftest passed - suite ran and the package was built\n'
