#!/usr/bin/env bash
#
# CM Insight - status report.
#
#   ./bin/status.sh [--help]
#
# States:
#   RUNNING             PID file holds a live CM Insight process (health also
#                       checked on loopback when curl is available)
#   UNTRACKED INSTANCE  no PID file, but loopback /api/health answers as CM Insight:
#                       an instance is running and is NOT tracked by run/cm-insight.pid
#   UNREACHABLE         the process is alive but the local health endpoint does not answer
#   STALE PID FILE      the PID file is invalid, dead, or owned by an unrelated process
#   STOPPED             no PID file and nothing answers on the configured port
#
# Only loopback addresses are ever probed or printed.
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 RUNNING + healthy, 3 STOPPED, 1 STALE PID FILE / UNTRACKED / UNREACHABLE.

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./bin/status.sh [--help]

Reports the local state of CM Insight:
  Status: RUNNING / STOPPED / STALE PID FILE / UNTRACKED INSTANCE
  Health: OK / UNREACHABLE / SKIPPED

UNTRACKED INSTANCE means an instance answers on the configured loopback port but no
run/cm-insight.pid tracks it (for example the PID file was removed while it ran, or a
previous start failed to publish it). Recover with ./bin/stop.sh --untracked.

Environment:
  CM_INSIGHT_CONFIG   configuration file (default conf/application.properties)

The health check always targets 127.0.0.1 with web.port from the configuration; a
non-loopback web.bind is never probed or printed. With web.port=0 the port is
random, so the health check is skipped and only the process is reported.

Exit codes: 0 running and healthy, 3 stopped, 1 stale PID file, untracked instance
or unreachable.
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

PID_FILE="${ROOT}/run/cm-insight.pid"
OUT_FILE="${ROOT}/logs/cm-insight.out"
CONFIG="${CM_INSIGHT_CONFIG:-${ROOT}/conf/application.properties}"

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
    # stderr is suppressed on the READ as well as the test: the file can disappear
    # between the two, which used to leak a bare shell error message.
    _cmd="$(cat "/proc/${_pid}/cmdline" 2>/dev/null | tr '\0' ' ' || true)"
    printf '%s' "${_cmd}"
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

config_value() {
  # $1 = file, $2 = literal key; prints the effective value or nothing.
  # Like java.util.Properties, a duplicated key uses the LAST occurrence.
  _file="$1"
  _key="$2"
  [ -r "${_file}" ] || return 0
  _esc="$(printf '%s' "${_key}" | sed 's/[.]/\\./g')"
  _line="$(sed -n "s/^[[:space:]]*${_esc}[[:space:]]*=[[:space:]]*//p" "${_file}" 2>/dev/null)"
  _line="${_line##*$'\n'}"
  _line="${_line%$'\r'}"
  printf '%s' "${_line}"
}

PORT=""
PORT_KNOWN=false
probe_health() {
  # Loopback-only probe. Sets PORT, PORT_KNOWN, PROBE_KIND (our|foreign|none|unknown),
  # PROBE_CODE. "our" requires the cm-insight service marker in the health body.
  PROBE_KIND="unknown"
  PROBE_CODE="000"
  PORT="$(config_value "${CONFIG}" web.port)"
  case "${PORT}" in ''|*[!0-9]*) PORT="8080" ;; esac
  if [ "${PORT}" = "0" ]; then return 0; fi
  PORT_KNOWN=true
  command -v curl >/dev/null 2>&1 || return 0
  _tmp="${TMPDIR:-/tmp}/cm-insight-probe.$$"
  PROBE_CODE="$(curl -sS -o "${_tmp}" -w '%{http_code}' --max-time 2 "http://127.0.0.1:${PORT}/api/health" 2>/dev/null || true)"
  _body="$(cat "${_tmp}" 2>/dev/null || true)"
  rm -f "${_tmp}" 2>/dev/null || true
  case "${PROBE_CODE}" in
    ''|000) PROBE_CODE="000"; PROBE_KIND="none" ;;
    200)
      case "${_body}" in
        *'"service":"cm-insight"'*) PROBE_KIND="our" ;;
        *) PROBE_KIND="foreign" ;;
      esac
      ;;
    *) PROBE_KIND="foreign" ;;
  esac
}

port_listener_pid() {
  # Prints the WINDOWS pid (Git Bash/MSYS) or Linux pid that owns 127.0.0.1:PORT,
  # or returns 1 when it cannot be resolved. Only genuinely PORT-SCOPED sources are
  # used: netstat -ano (Windows/MSYS, net-tools on Linux) or ss -ltnp (iproute2).
  # There is deliberately NO process-name scan, so an unrelated CM Insight JVM on a
  # different port can never be reported as this port's owner.
  if command -v netstat >/dev/null 2>&1; then
    _line="$(netstat -ano 2>/dev/null | tr -d '\r' | awk -v p="127.0.0.1:${PORT}" '$2==p {print; exit}' || true)"
    if [ -n "${_line}" ]; then
      _pid="$(printf '%s' "${_line}" | awk '{print $NF}')"
      case "${_pid}" in
        ''|*[!0-9]*) : ;;
        *) printf '%s' "${_pid}"; return 0 ;;
      esac
    fi
  fi
  if command -v ss >/dev/null 2>&1; then
    _pid="$(ss -ltnp 2>/dev/null | awk -v p="127.0.0.1:${PORT}" '$4==p && match($0, /pid=[0-9]+/) {print substr($0, RSTART+4, RLENGTH-4); exit}' || true)"
    if [ -n "${_pid}" ]; then printf '%s' "${_pid}"; return 0; fi
  fi
  return 1
}

probe_health
HEALTH_URL="http://127.0.0.1:${PORT}/api/health"
LISTEN_PID="$(port_listener_pid || true)"

if [ ! -f "${PID_FILE}" ]; then
  case "${PROBE_KIND}" in
    our)
      printf 'Status: UNTRACKED INSTANCE\n'
      printf 'Detail: an instance answers %s but no PID file tracks it (%s)\n' "${HEALTH_URL}" "${PID_FILE}"
      printf 'Health: OK (%s)\n' "${HEALTH_URL}"
      printf 'Fix:    ./bin/stop.sh --untracked stops it; ./bin/start.sh refuses to add a second one\n'
      exit 1
      ;;
    foreign)
      printf 'Status: STOPPED\n'
      printf 'PID:    none (%s)\n' "${PID_FILE}"
      printf 'WARN:   port %s answers HTTP %s but not the cm-insight health marker; another service holds it\n' "${PORT}" "${PROBE_CODE}" >&2
      exit 3
      ;;
    *)
      if [ -n "${LISTEN_PID}" ]; then
        printf 'Status: STOPPED\n'
        printf 'PID:    none (%s)\n' "${PID_FILE}"
        printf 'WARN:   127.0.0.1:%s is bound (owner PID %s) but does not answer as cm-insight: another service, or a hung/starting instance that is not tracked\n' "${PORT}" "${LISTEN_PID}" >&2
        printf 'WARN:   inspect it and, if it is CM Insight, recover with ./bin/stop.sh --untracked\n' >&2
        exit 3
      fi
      printf 'Status: STOPPED\n'
      printf 'PID:    none (%s)\n' "${PID_FILE}"
      exit 3
      ;;
  esac
fi

PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
PID="${PID%%$'\n'*}"

case "${PID}" in
  ''|*[!0-9]*)
    printf 'Status: STALE PID FILE\n'
    printf 'Detail: %s does not contain a valid PID (%s)\n' "${PID_FILE}" "${PID}"
    printf 'Fix:    ./bin/stop.sh removes it, then ./bin/start.sh\n'
    exit 1
    ;;
esac

if ! is_alive "${PID}"; then
  printf 'Status: STALE PID FILE\n'
  printf 'PID:    %s (not running)\n' "${PID}"
  printf 'Fix:    ./bin/stop.sh removes it, then ./bin/start.sh\n'
  exit 1
fi

set +e
proc_is_ours "${PID}"
IDENT_RC=$?
set -e
if [ "${IDENT_RC}" -eq 1 ]; then
  printf 'Status: STALE PID FILE\n'
  printf 'PID:    %s is a different process (recycled PID)\n' "${PID}"
  printf 'Fix:    ./bin/stop.sh removes the stale PID file, then ./bin/start.sh\n'
  exit 1
fi

printf 'Status: RUNNING\n'
printf 'PID:    %s\n' "${PID}"
printf 'Log:    %s\n' "${OUT_FILE}"
if [ "${IDENT_RC}" -eq 2 ]; then
  printf 'WARN:   the command line of PID %s could not be inspected on this platform\n' "${PID}" >&2
fi

if [ "${PORT}" = "0" ]; then
  printf 'Health: SKIPPED (web.port=0 binds a random free port; use a fixed port for health probing)\n'
  exit 0
fi

if command -v curl >/dev/null 2>&1; then
  if curl -fsS --max-time 2 "${HEALTH_URL}" >/dev/null 2>&1; then
    printf 'Health: OK (%s)\n' "${HEALTH_URL}"
    exit 0
  fi
  printf 'Status: UNREACHABLE\n'
  printf 'Health: no answer from %s within 2s (the process is alive)\n' "${HEALTH_URL}"
  printf 'Fix:    check %s\n' "${OUT_FILE}"
  exit 1
fi

printf 'Health: SKIPPED (curl is not available; process existence only)\n'
exit 0
