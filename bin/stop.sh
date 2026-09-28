#!/usr/bin/env bash
#
# CM Insight - stop the application.
#
#   ./bin/stop.sh [--force] [--timeout SECONDS] [--untracked] [--help]
#
# Behaviour (tracked, the normal path):
#   - no PID file              -> "not running" (exit 0)
#   - stale/dead PID           -> stale PID file is removed (exit 0)
#   - PID is a foreign process -> the PID file is removed and nothing is signalled
#   - otherwise SIGTERM, wait, and with --force SIGKILL after the timeout
#
# Behaviour (no PID file, --untracked):
#   - when loopback /api/health answers as CM Insight but no PID file tracks the
#     instance, --untracked stops the process that owns the configured port. The
#     target is found from the listening socket (netstat on Windows/MSYS, /proc on
#     Linux) and is only signalled after it is confirmed to be a JVM holding that
#     port; without --untracked stop.sh refuses and explains how to recover.
#
# The PID file's process command is inspected before a signal is sent (when the
# platform allows it: /proc/<pid>/cmdline, else ps), so a recycled PID is never
# killed.
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 stopped (or was not running), 1 still running / stop failed /
# untracked instance without --untracked, 2 usage error.

set -euo pipefail

FORCE=false
UNTRACKED=false
TIMEOUT="${CM_INSIGHT_STOP_TIMEOUT:-30}"

usage() {
  cat <<'USAGE'
Usage: ./bin/stop.sh [--force] [--timeout SECONDS] [--untracked] [--help]

Stops CM Insight using the PID file run/cm-insight.pid.

Options:
  --force             escalate to SIGKILL when the graceful stop times out
  --timeout SECONDS   how long to wait for a graceful exit (default 30,
                      env CM_INSIGHT_STOP_TIMEOUT)
  --untracked         also stop an instance that is running WITHOUT a PID file
                      (Status: UNTRACKED INSTANCE): the process that owns the
                      configured loopback port is located and stopped
  --help              show this help

Safety: before signalling, the target is compared with this application. A PID
that is not CM Insight is never signalled; its stale PID file is removed instead.
For --untracked the target must both answer the cm-insight health marker on the
configured loopback port and hold that port, and on Windows/MSYS it must be a java
executable. When a command line cannot be inspected at all, the signal is sent
with a warning - the PID file (or, for --untracked, the listening socket plus the
health marker) is the only ownership evidence available.

Environment:
  CM_INSIGHT_CONFIG   configuration file (default conf/application.properties)
  CM_INSIGHT_STOP_TIMEOUT  default graceful-stop timeout in seconds

Exit codes: 0 stopped or not running, 1 still running / stop failed / an untracked
instance exists but --untracked was not given, 2 usage error.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --force) FORCE=true; shift ;;
    --untracked) UNTRACKED=true; shift ;;
    --timeout)
      [ "$#" -ge 2 ] || { printf 'ERROR: --timeout requires a value in seconds\n' >&2; exit 2; }
      TIMEOUT="$2"
      shift 2
      ;;
    *) printf 'ERROR: unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

case "${TIMEOUT}" in
  ''|*[!0-9]*) printf 'ERROR: --timeout must be a non-negative integer (got: %s)\n' "${TIMEOUT}" >&2; exit 2 ;;
esac

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
[ -d "${SCRIPT_DIR}" ] || { printf 'ERROR: cannot locate script directory: %s\n' "${SCRIPT_DIR}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)" || { printf 'ERROR: cannot resolve repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

PID_FILE="${ROOT}/run/cm-insight.pid"
CONFIG="${CM_INSIGHT_CONFIG:-${ROOT}/conf/application.properties}"

log_ok() { printf 'OK: %s\n' "$*"; }
log_warn() { printf 'WARN: %s\n' "$*" >&2; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

is_alive() {
  # $1 = pid; false for a process that has exited (including a zombie)
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

config_value() {
  # $1 = file, $2 = literal key; prints the effective value or nothing
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
  # Loopback-only probe: PORT, PORT_KNOWN, PROBE_KIND (our|foreign|none|unknown), PROBE_CODE
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

wait_for_exit() {
  # $1 = pid, $2 = seconds; true when the process is gone
  _pid="$1"
  _left="$2"
  while [ "${_left}" -gt 0 ]; do
    if ! is_alive "${_pid}"; then return 0; fi
    sleep 1
    _left=$((_left - 1))
  done
  if ! is_alive "${_pid}"; then return 0; fi
  return 1
}

listener_pid() {
  # Prints the WINDOWS pid (Git Bash/MSYS) or Linux pid that owns 127.0.0.1:PORT,
  # or returns 1 when the owner cannot be resolved. Only genuinely PORT-SCOPED
  # sources are used: netstat -ano (Windows/MSYS, net-tools on Linux) or ss -ltnp
  # (iproute2 on Linux). There is deliberately NO process-name scan: an unrelated
  # CM Insight JVM on another port must never be reported - and never signalled -
  # as this port's owner ("unknown owner" must stay unknown).
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

owner_exe() {
  # $1 = pid from listener_pid (a Windows pid under Git Bash/MSYS); prints its
  # executable path when the platform exposes it
  command -v ps >/dev/null 2>&1 || return 0
  ps -W 2>/dev/null | tr -d '\r' | awk -v w="$1" '$4==w {print $8; exit}'
}

owner_is_jvm() {
  # 0 = the port owner is a JVM, 1 = something else, 2 = cannot tell
  _exe="$(owner_exe "$1")"
  if [ -n "${_exe}" ]; then
    case "$(basename -- "${_exe}")" in
      java|java.exe|javaw|javaw.exe) return 0 ;;
      *) return 1 ;;
    esac
  fi
  if [ -r "/proc/$1/cmdline" ]; then
    _c="$(cat "/proc/$1/cmdline" 2>/dev/null | tr '\0' ' ' || true)"
    case "${_c}" in
      *java*) return 0 ;;
      *) return 1 ;;
    esac
  fi
  return 2
}

if [ ! -f "${PID_FILE}" ]; then
  probe_health
  LPID="$(listener_pid || true)"
  OWNER_JVM=false
  if [ -n "${LPID}" ]; then
    set +e
    owner_is_jvm "${LPID}"
    OWNER_RC=$?
    set -e
    if [ "${OWNER_RC}" -eq 0 ]; then OWNER_JVM=true; fi
  fi

  # An untracked instance is either confirmed by the health marker, or is a JVM
  # holding the configured port (a hung/starting instance that never published a
  # PID file). Anything else on the port belongs to somebody else.
  if [ "${PROBE_KIND}" = "our" ] || { [ -n "${LPID}" ] && [ "${OWNER_JVM}" = true ]; }; then
      if [ "${UNTRACKED}" != true ]; then
        if [ "${PROBE_KIND}" = "our" ]; then
          printf 'ERROR: an instance answers http://127.0.0.1:%s/api/health but is not tracked by %s; refusing to guess.\n' "${PORT}" "${PID_FILE}" >&2
        else
          printf 'ERROR: 127.0.0.1:%s is held by a JVM (owner PID %s) that does not answer the cm-insight health marker and is not tracked by %s; refusing to guess.\n' "${PORT}" "${LPID}" "${PID_FILE}" >&2
        fi
        printf '       Stop it explicitly with ./bin/stop.sh --untracked (add --force to escalate), or inspect it with ./bin/status.sh.\n' >&2
        exit 1
      fi

      if [ -z "${LPID}" ]; then
        printf 'ERROR: an untracked instance answers on 127.0.0.1:%s but the process owning that port could not be identified on this platform (netstat unavailable and no /proc scan match).\n' "${PORT}" >&2
        printf '       Identify it manually (Windows: tasklist | findstr java, then taskkill /PID <pid>; Linux: ss -ltnp) and stop it there.\n' >&2
        exit 1
      fi
      if [ "${PROBE_KIND}" != "our" ]; then
        log_warn "the owner of 127.0.0.1:${PORT} (PID ${LPID}) did not answer the cm-insight health marker (HTTP ${PROBE_CODE}); proceeding because --untracked was given and the owner is a JVM"
      fi

      # Map to a signalable pid (Git Bash/MSYS: Windows pid -> MSYS pid).
      TARGET_PID="${LPID}"
      MSYS_MAP=""
      if command -v ps >/dev/null 2>&1; then
        MSYS_MAP="$(ps -W 2>/dev/null | tr -d '\r' | awk -v w="${LPID}" '$4==w {print $1; exit}')"
      fi
      if [ -n "${MSYS_MAP}" ]; then TARGET_PID="${MSYS_MAP}"; fi

      log_ok "stopping the UNTRACKED instance (pid ${TARGET_PID} owns 127.0.0.1:${PORT}) with SIGTERM; waiting up to ${TIMEOUT}s"
      if ! kill -TERM "${TARGET_PID}" 2>/dev/null; then
        if is_alive "${TARGET_PID}"; then
          fail "cannot send SIGTERM to PID ${TARGET_PID}; check permissions (are you the owner of the process?)"
        fi
      fi

      if ! wait_for_exit "${TARGET_PID}" "${TIMEOUT}"; then
        if [ "${FORCE}" = true ]; then
          log_warn "graceful stop timed out after ${TIMEOUT}s; sending SIGKILL to PID ${TARGET_PID}"
          kill -KILL "${TARGET_PID}" 2>/dev/null || true
          if is_alive "${TARGET_PID}" && command -v taskkill >/dev/null 2>&1; then
            taskkill //F //PID "${LPID}" >/dev/null 2>&1 || true
          fi
          wait_for_exit "${TARGET_PID}" 10 || fail "PID ${TARGET_PID} is still present after SIGKILL"
        else
          printf 'ERROR: the untracked instance (PID %s) is still running after %ss; retry with ./bin/stop.sh --untracked --force\n' "${TARGET_PID}" "${TIMEOUT}" >&2
          exit 1
        fi
      fi

      probe_health
      if [ "${PROBE_KIND}" = "our" ]; then
        fail "the untracked instance still answers on 127.0.0.1:${PORT} after stopping PID ${TARGET_PID}; it may have been restarted (check ./bin/status.sh)"
      fi
      log_ok "the untracked instance is stopped and 127.0.0.1:${PORT} no longer answers as CM Insight"
      exit 0
  fi

  if [ "${PROBE_KIND}" = "foreign" ] || [ -n "${LPID}" ]; then
    log_ok "CM Insight is not running (an unrelated service holds 127.0.0.1:${PORT}); nothing was signalled"
  else
    log_ok "CM Insight is not running (no PID file ${PID_FILE})"
  fi
  exit 0
fi

# ---------------------------------------------------------------------------
# tracked instance (unchanged behaviour)
# ---------------------------------------------------------------------------
PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
PID="${PID%%$'\n'*}"

case "${PID}" in
  ''|*[!0-9]*)
    log_warn "PID file ${PID_FILE} does not contain a valid PID ('${PID}'); removing the stale file"
    rm -f "${PID_FILE}"
    log_ok "CM Insight is not running"
    exit 0
    ;;
esac

if ! is_alive "${PID}"; then
  log_warn "PID ${PID} from ${PID_FILE} is not running; removing the stale PID file"
  rm -f "${PID_FILE}"
  log_ok "CM Insight is not running"
  exit 0
fi

set +e
proc_is_ours "${PID}"
IDENT_RC=$?
set -e
if [ "${IDENT_RC}" -eq 1 ]; then
  log_warn "PID ${PID} from ${PID_FILE} is a different process; not signalling it and removing the stale PID file"
  rm -f "${PID_FILE}"
  log_ok "CM Insight is not running"
  exit 0
fi
if [ "${IDENT_RC}" -eq 2 ]; then
  log_warn "cannot inspect the command line of PID ${PID}; sending the signal based on the PID file"
fi

log_ok "stopping CM Insight (PID ${PID}) with SIGTERM; waiting up to ${TIMEOUT}s"
if ! kill -TERM "${PID}" 2>/dev/null; then
  if is_alive "${PID}"; then
    fail "cannot send SIGTERM to PID ${PID}; check permissions (are you the owner of the process?)"
  fi
  rm -f "${PID_FILE}"
  log_ok "CM Insight stopped"
  exit 0
fi

if wait_for_exit "${PID}" "${TIMEOUT}"; then
  rm -f "${PID_FILE}"
  log_ok "CM Insight stopped (PID ${PID})"
  exit 0
fi

if [ "${FORCE}" = true ]; then
  set +e
  proc_is_ours "${PID}"
  IDENT_RC=$?
  set -e
  if [ "${IDENT_RC}" -eq 1 ]; then
    log_warn "PID ${PID} was reused by a different process while waiting; not sending SIGKILL and removing the stale PID file"
    rm -f "${PID_FILE}"
    log_ok "CM Insight is not running"
    exit 0
  fi
  log_warn "graceful stop timed out after ${TIMEOUT}s; sending SIGKILL to PID ${PID}"
  if ! kill -KILL "${PID}" 2>/dev/null; then
    if is_alive "${PID}"; then fail "cannot send SIGKILL to PID ${PID}; check permissions"; fi
  fi
  if wait_for_exit "${PID}" 10; then
    rm -f "${PID_FILE}"
    log_ok "CM Insight stopped (SIGKILL, PID ${PID})"
    exit 0
  fi
  fail "PID ${PID} is still present after SIGKILL; the PID file was kept for diagnosis"
fi

printf 'ERROR: CM Insight (PID %s) is still running after %ss. Review logs/cm-insight.out, then retry with ./bin/stop.sh --force\n' "${PID}" "${TIMEOUT}" >&2
exit 1
