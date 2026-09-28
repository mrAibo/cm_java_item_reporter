#!/usr/bin/env bash
#
# CM Insight - clean build artifacts (and optionally runtime state).
#
#   ./bin/clean.sh [--all] [--help]
#
# Refuses to delete anything while CM Insight is running (verified by the PID
# file and the process command line). Build output (build/) is always removed;
# --all also removes runtime state (run/ and logs/).
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 cleaned, 1 refused (application running) or cleaning incomplete, 2 usage error.

set -euo pipefail

ALL=false

usage() {
  cat <<'USAGE'
Usage: ./bin/clean.sh [--all] [--help]

Removes build output:
  build/     classes, test-classes, jar, .version

With --all it also removes runtime state:
  run/       PID file
  logs/      application log

Options:
  --all     also remove run/ and logs/
  --help    show this help

Safety: the command refuses to delete anything while the PID file points at a
live CM Insight process. A PID file that points at a dead or unrelated process
is removed as stale and cleaning continues.

Exit codes: 0 cleaned, 1 refused (application is running) or cleaning incomplete, 2 usage error.
USAGE
}

for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage; exit 0 ;;
    --all) ALL=true ;;
    *) printf 'ERROR: unknown argument: %s\n' "${arg}" >&2; usage >&2; exit 2 ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
[ -d "${SCRIPT_DIR}" ] || { printf 'ERROR: cannot locate script directory: %s\n' "${SCRIPT_DIR}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)" || { printf 'ERROR: cannot resolve repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

PID_FILE="${ROOT}/run/cm-insight.pid"

is_alive() {
  _pid="$1"
  kill -0 "${_pid}" 2>/dev/null || return 1
  if [ -r "/proc/${_pid}/stat" ]; then
    _state="$(sed -n 's/^[^)]*) \([A-Za-z]\).*/\1/p' "/proc/${_pid}/stat" 2>/dev/null)"
    [ "${_state}" = "Z" ] && return 1
  fi
  return 0
}

proc_cmdline() {
  _pid="$1"
  if [ -r "/proc/${_pid}/cmdline" ]; then
    tr '\0' ' ' < "/proc/${_pid}/cmdline" 2>/dev/null || true
    return 0
  fi
  if command -v ps >/dev/null 2>&1; then
    ps -p "${_pid}" -o args= 2>/dev/null || true
  fi
}

proc_is_ours() {
  # 0 = our application, 1 = a different process, 2 = cannot tell
  _cmd="$(proc_cmdline "$1")"
  _cmd="${_cmd%%$'\n'*}"
  if [ -z "${_cmd}" ]; then return 2; fi
  case "${_cmd}" in
    *cm-insight.jar*|*cminsight.app.Main*|*bin/cm-insight*) return 0 ;;
    *) return 1 ;;
  esac
}

if [ -f "${PID_FILE}" ]; then
  PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
  PID="${PID%%$'\n'*}"
  case "${PID}" in
    ''|*[!0-9]*)
      printf 'WARN: %s does not contain a valid PID; treating it as stale\n' "${PID_FILE}" >&2
      rm -f "${PID_FILE}"
      ;;
    *)
      if is_alive "${PID}"; then
        set +e
        proc_is_ours "${PID}"
        IDENT_RC=$?
        set -e
        if [ "${IDENT_RC}" -eq 1 ]; then
          printf 'WARN: PID %s belongs to a different process; removing the stale PID file\n' "${PID}" >&2
          rm -f "${PID_FILE}"
        else
          printf 'ERROR: CM Insight is running (PID %s); stop it first with ./bin/stop.sh\n' "${PID}" >&2
          exit 1
        fi
      else
        printf 'WARN: PID %s is not running; removing the stale PID file\n' "${PID}" >&2
        rm -f "${PID_FILE}"
      fi
      ;;
  esac
fi

CLEAN_FAILED=0

remove_path() {
  # $1 = path, $2 = display label; prints OK/ERROR and reports failure via status
  if [ ! -e "$1" ]; then
    printf 'OK: %s was already absent\n' "$2"
    return 0
  fi
  if rm -rf "$1"; then
    printf 'OK: removed %s\n' "$2"
    return 0
  fi
  printf 'ERROR: could not completely remove %s (a file inside it is still open by a running build, test or JVM). Stop that process and retry.\n' "$2" >&2
  return 1
}

remove_path "${ROOT}/build" "build/" || CLEAN_FAILED=1

if [ "${ALL}" = true ]; then
  remove_path "${ROOT}/run" "run/" || CLEAN_FAILED=1
  remove_path "${ROOT}/logs" "logs/" || CLEAN_FAILED=1
else
  printf 'OK: kept run/ and logs/ (use --all to remove them as well)\n'
fi

if [ "${CLEAN_FAILED}" -ne 0 ]; then
  printf 'ERROR: cleaning was incomplete; see the messages above\n' >&2
  exit 1
fi
