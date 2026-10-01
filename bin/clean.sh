#!/usr/bin/env bash
#
# CM Insight - clean build artifacts (and optionally runtime state).
#
#   ./bin/clean.sh [--all] [--help]
#
# Refuses to delete anything while CM Insight is running (verified by the PID
# file and the process command line). Build output (build/) is always removed;
# --all also removes the runtime state directories (run/ and logs/).
#
# Paths (Goal 01A section D): the runtime state follows the SAME application home as
# bin/start.sh, bin/status.sh, bin/stop.sh and bin/cm-insight:
#   APP_HOME = ${CM_INSIGHT_HOME:-<repository root>}
#   run directory = ${CM_INSIGHT_RUN_DIR:-${APP_HOME}/run}
#   log directory = ${CM_INSIGHT_LOG_DIR:-${APP_HOME}/logs}
# so a relocated home is cleaned in place instead of deleting the installation's run/
# and logs/. build/ is an installation path and always stays on the repository root.
# The PID file is read from the run directory above - the one that decides whether it
# is safe to delete.
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

With --all it also removes the runtime state the lifecycle scripts use:
  <home>/run/    PID file        (CM_INSIGHT_RUN_DIR overrides <home>)
  <home>/logs/   application log (CM_INSIGHT_LOG_DIR overrides <home>)

Options:
  --all     also remove the run/ and logs/ directories
  --help    show this help

Safety: the command refuses to delete anything while the PID file in the run
directory points at a live CM Insight process. A PID file that points at a dead or
unrelated process is removed as stale and cleaning continues.

Environment:
  CM_INSIGHT_HOME        application home (default: the repository root)
  CM_INSIGHT_RUN_DIR     PID file directory (default <home>/run)
  CM_INSIGHT_LOG_DIR     log directory (default <home>/logs)

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

LIB_DIR="${SCRIPT_DIR}/lib"
for module in cm-insight-addr.sh cm-insight-lifecycle.sh; do
  [ -r "${LIB_DIR}/${module}" ] || { printf 'ERROR: required module is missing: %s\n' "${LIB_DIR}/${module}" >&2; exit 2; }
done
# shellcheck source=/dev/null
. "${LIB_DIR}/cm-insight-lifecycle.sh"

# One path rule (Goal 01A section D), shared with bin/cm-insight and the lifecycle
# scripts: the application home is CM_INSIGHT_HOME when set, else the repository root.
APP_HOME="$(ci_effective_home "${ROOT}")"
RUN_DIR="${CM_INSIGHT_RUN_DIR:-${APP_HOME}/run}"
LOG_DIR="${CM_INSIGHT_LOG_DIR:-${APP_HOME}/logs}"
PID_FILE="${RUN_DIR}/cm-insight.pid"
CONFIG="$(ci_effective_config "${APP_HOME}")"
PORT="$(ci_effective_port "${CONFIG}")"
BIND="$(ci_effective_bind "${CONFIG}")"

if [ -f "${PID_FILE}" ]; then
  PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
  PID="${PID%%$'\n'*}"
  case "${PID}" in
    ''|*[!0-9]*)
      printf 'WARN: %s does not contain a valid PID; treating it as stale\n' "${PID_FILE}" >&2
      rm -f "${PID_FILE}"
      ;;
    *)
      # Refuse while the instance is behind that PID in EITHER pid namespace or owns a
      # listening socket that serves the configured bind: "not in the process table" is
      # not proof that nothing is running (see ci_process_exists / H-3, H-4).
      if ci_pid_is_running "${PID}" "${PORT}" "${BIND}"; then
        set +e
        ci_proc_identity "${PID}"
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

remove_path "${ROOT}/build" "build/ (${ROOT}/build)" || CLEAN_FAILED=1

if [ "${ALL}" = true ]; then
  remove_path "${RUN_DIR}" "run/ (${RUN_DIR})" || CLEAN_FAILED=1
  remove_path "${LOG_DIR}" "logs/ (${LOG_DIR})" || CLEAN_FAILED=1
else
  printf 'OK: kept %s and %s (use --all to remove them as well)\n' "${RUN_DIR}" "${LOG_DIR}"
fi

if [ "${CLEAN_FAILED}" -ne 0 ]; then
  printf 'ERROR: cleaning was incomplete; see the messages above\n' >&2
  exit 1
fi
