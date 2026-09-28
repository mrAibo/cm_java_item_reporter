#!/usr/bin/env bash
#
# CM Insight - start the application detached, verify it is really up.
#
#   ./bin/start.sh [--timeout SECONDS] [--help] [-- application arguments...]
#
# What it does:
#   1. reads run/cm-insight.pid; a valid, live, CM Insight PID short-circuits as
#      "already running" and a stale/foreign PID file is cleaned up
#   2. refuses to start when loopback /api/health already answers as CM Insight
#      but no PID file tracks it (an UNTRACKED INSTANCE), and also refuses when
#      another service already holds the configured port
#   3. runs bin/doctor.sh as a pre-flight check
#   4. launches bin/cm-insight with nohup, appending to logs/cm-insight.out
#   5. health-checks http://127.0.0.1:<web.port>/api/health until the timeout,
#      verifying on every iteration that the PID is still our process
#   6. PUBLISHES run/cm-insight.pid atomically (temp file + rename) ONLY AFTER the
#      instance is confirmed: health OK, or the process verified in the
#      process-only modes (--timeout 0, web.port=0). A failed start never writes
#      and never deletes the PID file, so an instance that is already running can
#      never lose its tracking because of a later failed start
#   7. on failure names the actual condition and prints the decisive log line(s)
#      together with the tail of the log
#
# Tracking safety:
#   * the PID file is only ever created by a start that confirmed its instance;
#   * on any failure path only the private temp file is removed;
#   * a PID whose command line is not this application is never signalled.
#
# Untracked instance (recovery):
#   ./bin/status.sh              -> "Status: UNTRACKED INSTANCE" (exit 1)
#   ./bin/stop.sh --untracked    -> stops the process that owns the configured port
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 started, 1 startup failure or refusal to duplicate an instance,
# 2 usage error.

set -euo pipefail

TIMEOUT="${CM_INSIGHT_START_TIMEOUT:-30}"
APP_ARGS=()

usage() {
  cat <<'USAGE'
Usage: ./bin/start.sh [--timeout SECONDS] [--help] [-- application arguments...]

Starts CM Insight in the background and waits until it answers a local health check.

Options:
  --timeout SECONDS   how long to wait for the health check (default 30, env
                      CM_INSIGHT_START_TIMEOUT). 0 means "check the process only".
  --help              show this help

With web.port=0 the application binds a random free port, so the health URL cannot
be derived from the configuration and only the process is verified.

Refuses to start (exit 1) when:
  * the configured loopback port already answers /api/health as CM Insight but no
    PID file tracks it -> an UNTRACKED INSTANCE; inspect it with ./bin/status.sh
    and stop it with ./bin/stop.sh --untracked, or
  * another service already holds the configured port.
A PID file that tracks a live CM Insight process is the "already running" fast
path (exit 0, nothing started).

Everything after "--" is passed to the application launcher (bin/cm-insight).

Environment:
  JAVA_HOME              JDK 17+ home (POSIX or Windows form)
  CM_INSIGHT_CONFIG      configuration file (default conf/application.properties)
  CM_INSIGHT_START_TIMEOUT  default health-check timeout in seconds

Files:
  run/cm-insight.pid     PID file, published atomically only after the instance is
                         confirmed (temp file + rename)
  logs/cm-insight.out    application stdout/stderr log

Exit codes: 0 started (health confirmed or process alive with curl unavailable),
1 startup failure or refusal to start a duplicate instance, 2 usage error.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --timeout)
      [ "$#" -ge 2 ] || { printf 'ERROR: --timeout requires a value in seconds\n' >&2; exit 2; }
      TIMEOUT="$2"
      shift 2
      ;;
    --)
      shift
      while [ "$#" -gt 0 ]; do APP_ARGS+=("$1"); shift; done
      ;;
    *)
      printf 'ERROR: unknown argument: %s (use -- before application arguments)\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "${TIMEOUT}" in
  ''|*[!0-9]*) printf 'ERROR: --timeout must be a non-negative integer (got: %s)\n' "${TIMEOUT}" >&2; exit 2 ;;
esac

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
[ -d "${SCRIPT_DIR}" ] || { printf 'ERROR: cannot locate script directory: %s\n' "${SCRIPT_DIR}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)" || { printf 'ERROR: cannot resolve repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

RUN_DIR="${ROOT}/run"
LOG_DIR="${ROOT}/logs"
PID_FILE="${RUN_DIR}/cm-insight.pid"
OUT_FILE="${LOG_DIR}/cm-insight.out"
CONFIG="${CM_INSIGHT_CONFIG:-${ROOT}/conf/application.properties}"
PID_TMP=""

cleanup_tmp() {
  # Only ever removes the private candidate file: the published PID file is not
  # ours to delete on a failure path.
  if [ -n "${PID_TMP}" ]; then rm -f "${PID_TMP}" 2>/dev/null || true; fi
}
trap cleanup_tmp EXIT

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
  # $1 = pid; prints the command line when the platform allows it, else nothing
  _pid="$1"
  if [ -r "/proc/${_pid}/cmdline" ]; then
    # stderr is suppressed on the READ, not only on the test: between the -r test
    # and the open the process can exit and the file disappear, which used to leak
    # a bare "/proc/<pid>/cmdline: No such file or directory".
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
  # Loopback-only probe of the configured port. Sets:
  #   PORT, PORT_KNOWN, PROBE_KIND = our | foreign | none | unknown, PROBE_CODE
  # "our" means the health body carries the cm-insight service marker, so a
  # different HTTP service on the same port is reported as foreign, not as ours.
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
  # used: the socket table via netstat -ano (Windows/MSYS, net-tools on Linux) or
  # ss -ltnp (iproute2 on Linux). There is deliberately NO process-name scan: an
  # unrelated CM Insight JVM on another port must never be mistaken for this port's
  # owner, and an unknown owner must stay unknown (callers treat that as "no
  # conflict", never as "guess").
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

run_log_slice() {
  # Only the bytes THIS start attempt appended. The log is opened in append mode across restarts,
  # so grepping the whole file reports a stale cause from an earlier run as the reason for the
  # current failure - for example an old "Configuration file not found" line made a bind failure
  # look like a configuration problem.
  [ -f "${OUT_FILE}" ] || return 0
  tail -c +"$((LOG_START + 1))" "${OUT_FILE}" 2>/dev/null || true
}

startup_reason() {
  # $1 = log file; prints a one-line primary reason for a failed start, from this run's output only
  _log="$1"
  if [ ! -r "${_log}" ]; then
    printf 'no log output was captured'
    return 0
  fi
  _slice="$(run_log_slice)"
  if [ -z "${_slice}" ]; then
    printf 'the application exited before writing anything to the log'
    return 0
  fi
  if printf '%s\n' "${_slice}" | grep -qiE 'Address already in use|BindException'; then
    printf 'the configured address 127.0.0.1:%s is already bound by another process (bind failed)' "${PORT:-?}"
  elif printf '%s\n' "${_slice}" | grep -qi 'Configuration file not found'; then
    printf 'the configuration file could not be read'
  elif printf '%s\n' "${_slice}" | grep -qiE 'Unsupported database vendor|InvalidPathException|ConfigException'; then
    printf 'the configuration was rejected at startup'
  elif printf '%s\n' "${_slice}" | grep -qiE 'OutOfMemoryError|java.lang.OutOfMemory'; then
    printf 'the JVM ran out of memory'
  else
    printf 'the application exited before it became healthy (no recognised cause in this run)'
  fi
}

print_decisive_lines() {
  # surfaces this run's decisive lines so the operator does not have to read a whole tail
  _lines="$(run_log_slice | grep -iE 'Address already in use|BindException|Configuration file not found|InvalidPathException|Unsupported database vendor|ConfigException|OutOfMemoryError|ERROR' 2>/dev/null | tail -n 3)"
  if [ -n "${_lines}" ]; then
    printf -- '--- decisive log line(s) ---\n' >&2
    printf '%s\n' "${_lines}" >&2
  fi
}

print_log_tail() {
  _tail="$(run_log_slice | tail -n 40)"
  if [ -n "${_tail}" ]; then
    printf -- '--- tail of this start attempt (%s) ---\n' "${OUT_FILE}" >&2
    printf '%s\n' "${_tail}" >&2
    printf -- '--- end of log ---\n' >&2
  else
    printf -- '(no output captured yet in %s)\n' "${OUT_FILE}" >&2
  fi
}

fail_start() {
  # $1 = primary reason, $2 = optional fix hint
  printf 'ERROR: %s\n' "$1" >&2
  [ -z "${2:-}" ] || printf '       %s\n' "$2" >&2
  print_decisive_lines "${OUT_FILE}"
  print_log_tail
  exit 1
}

mkdir -p "${RUN_DIR}" "${LOG_DIR}" || fail "cannot create ${RUN_DIR} and ${LOG_DIR}; check directory permissions"

# ---------------------------------------------------------------------------
# 1. already running? (never signal a foreign process, never destroy tracking)
# ---------------------------------------------------------------------------
if [ -f "${PID_FILE}" ]; then
  OLD_PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
  OLD_PID="${OLD_PID%%$'\n'*}"
  case "${OLD_PID}" in
    ''|*[!0-9]*)
      log_warn "PID file ${PID_FILE} does not contain a valid PID ('${OLD_PID}'); removing it"
      rm -f "${PID_FILE}"
      ;;
    *)
      if is_alive "${OLD_PID}"; then
        set +e
        proc_is_ours "${OLD_PID}"
        IDENT_RC=$?
        set -e
        case "${IDENT_RC}" in
          0) log_ok "CM Insight is already running (PID ${OLD_PID}); nothing to do"; exit 0 ;;
          1) log_warn "PID file ${PID_FILE} points at PID ${OLD_PID}, which is a different process; removing the stale PID file and continuing" ;;
          *) log_warn "PID file ${PID_FILE} points at live PID ${OLD_PID} but its command line cannot be inspected; removing the stale PID file and continuing" ;;
        esac
        rm -f "${PID_FILE}"
      else
        log_warn "removing stale PID file ${PID_FILE} (PID ${OLD_PID} is not running)"
        rm -f "${PID_FILE}"
      fi
      ;;
  esac
fi

# ---------------------------------------------------------------------------
# 2. untracked instance / occupied port pre-flight (loopback only)
# ---------------------------------------------------------------------------
probe_health
LISTEN_PID="$(port_listener_pid || true)"
case "${PROBE_KIND}" in
  our)
    printf 'ERROR: an instance is already serving http://127.0.0.1:%s/api/health but is not tracked by %s; refusing to start a second instance.\n' "${PORT}" "${PID_FILE}" >&2
    printf '       Inspect it with ./bin/status.sh (Status: UNTRACKED INSTANCE) and stop it with ./bin/stop.sh --untracked. No PID file was written or removed.\n' >&2
    exit 1
    ;;
  foreign)
    printf 'ERROR: 127.0.0.1:%s already answers HTTP %s without the cm-insight health marker; refusing to start (another service holds the port, or an instance reports an unexpected health body).\n' "${PORT}" "${PROBE_CODE}" >&2
    printf '       Stop that service or choose another web.port in %s.\n' "${CONFIG}" >&2
    exit 1
    ;;
  *)
    if [ -n "${LISTEN_PID}" ]; then
      printf 'ERROR: 127.0.0.1:%s is already bound (owner PID %s) but /api/health does not answer as CM Insight; refusing to start.\n' "${PORT}" "${LISTEN_PID}" >&2
      printf '       That is another service, a hung instance, or an instance that was not published in %s (it is still untracked).\n' "${PID_FILE}" >&2
      printf '       Inspect it with ./bin/status.sh, stop an untracked instance with ./bin/stop.sh --untracked, or choose another web.port.\n' >&2
      exit 1
    fi
    ;;
esac
if [ "${PROBE_KIND}" = "unknown" ]; then
  if [ "${PORT_KNOWN}" = true ]; then
    log_warn "the port could not be probed (curl unavailable); the untracked-instance and port-conflict checks were skipped"
  else
    log_warn "web.port=0 binds a random free port; the untracked-instance and port-conflict checks cannot run"
  fi
fi

# ---------------------------------------------------------------------------
# 3. pre-flight
# ---------------------------------------------------------------------------
if ! "${SCRIPT_DIR}/doctor.sh"; then
  fail "pre-flight doctor check failed (see the ERROR lines above); fix the environment, or run ./bin/doctor.sh for details"
fi

APP_JAR="${ROOT}/build/cm-insight.jar"
if [ ! -f "${APP_JAR}" ]; then
  fail "${APP_JAR} not found; build the application first with ./build.sh"
fi

# ---------------------------------------------------------------------------
# 4. launch (the PID file is deliberately NOT touched yet)
# ---------------------------------------------------------------------------
# The log is appended across restarts, so record where this attempt starts. Every diagnosis below
# then reads only this run's output instead of reporting a stale cause from an earlier attempt.
LOG_START=0
if [ -f "${OUT_FILE}" ]; then
  LOG_START="$(wc -c < "${OUT_FILE}" 2>/dev/null || echo 0)"
fi
case "${LOG_START}" in ''|*[!0-9]*) LOG_START=0 ;; esac

nohup "${SCRIPT_DIR}/cm-insight" ${APP_ARGS[@]+"${APP_ARGS[@]}"} >>"${OUT_FILE}" 2>&1 &
PID=$!

PID_TMP="${PID_FILE}.tmp.$$"
if ! printf '%s\n' "${PID}" > "${PID_TMP}"; then
  fail "cannot write the candidate PID file in ${RUN_DIR}; check directory permissions"
fi

USE_CURL=true
SKIP_REASON=""
if ! command -v curl >/dev/null 2>&1; then
  USE_CURL=false
  SKIP_REASON="curl is unavailable"
  log_warn "curl is not available; falling back to a process-existence check only"
fi
if [ "${TIMEOUT}" -eq 0 ]; then
  USE_CURL=false
  SKIP_REASON="--timeout 0"
  TIMEOUT=3
  log_warn "--timeout 0: verifying only that the process starts and stays alive (3s grace period)"
fi
if [ "${PORT}" = "0" ]; then
  USE_CURL=false
  SKIP_REASON="web.port=0 binds an ephemeral port"
  log_warn "web.port=0 binds a random free port, so the health URL cannot be derived from the configuration; verifying the process only"
fi

HEALTH_URL="http://127.0.0.1:${PORT}/api/health"

WAITED=0
HEALTH_OK=false
PROCESS_ONLY=false
PROC_GONE=false
RECYCLED=false
while : ; do
  if ! is_alive "${PID}"; then PROC_GONE=true; break; fi

  set +e
  proc_is_ours "${PID}"
  IDENT_RC=$?
  set -e
  if [ "${IDENT_RC}" -eq 1 ]; then RECYCLED=true; break; fi

  if [ "${USE_CURL}" = true ]; then
    if curl -fsS --max-time 2 "${HEALTH_URL}" >/dev/null 2>&1; then HEALTH_OK=true; break; fi
  elif [ "${WAITED}" -ge 3 ]; then
    PROCESS_ONLY=true
    break
  fi

  if [ "${WAITED}" -ge "${TIMEOUT}" ]; then break; fi
  sleep 1
  WAITED=$((WAITED + 1))
done

# ---------------------------------------------------------------------------
# 5. publish the PID file only for a confirmed instance
# ---------------------------------------------------------------------------
if [ "${HEALTH_OK}" = true ] || [ "${PROCESS_ONLY}" = true ]; then
  if ! mv -f "${PID_TMP}" "${PID_FILE}"; then
    printf 'ERROR: the instance is running (PID %s) but the PID file %s could not be published, so it is UNTRACKED.\n' "${PID}" "${PID_FILE}" >&2
    printf '       Stop it with ./bin/stop.sh --untracked once the directory is writable.\n' >&2
    exit 1
  fi
  PID_TMP=""
fi

if [ "${HEALTH_OK}" = true ]; then
  log_ok "CM Insight started (PID ${PID}); health OK on ${HEALTH_URL}"
  log_ok "PID file published: ${PID_FILE}"
  log_ok "log: ${OUT_FILE}"
  exit 0
fi

if [ "${PROCESS_ONLY}" = true ]; then
  log_ok "CM Insight started (PID ${PID}); process verified and still alive (health check skipped: ${SKIP_REASON})"
  log_ok "PID file published: ${PID_FILE}"
  log_ok "log: ${OUT_FILE}"
  exit 0
fi

# ---------------------------------------------------------------------------
# 6. failures: name the real condition and leave existing tracking intact
# ---------------------------------------------------------------------------
REASON="$(startup_reason "${OUT_FILE}")"

if [ "${PROC_GONE}" = true ]; then
  # The child's exit code is deliberately NOT collected with `wait`.
  #
  # bin/cm-insight ends in `exec java ...`, so the child becomes a native Windows process. Under
  # MSYS/Git Bash that process can sit as a zombie (/proc reports state Z and `kill -0` says it is
  # gone) while `wait` never returns at all. start.sh then blocked forever *after* it already knew
  # the start had failed, so the operator got no message whatsoever. Measured: 3 hangs in 5 cycles.
  # startup_reason plus the log slice below name the cause, which is what an operator needs.
  fail_start "CM Insight exited during startup (PID ${PID}): ${REASON}." \
             "Nothing is running and no PID file was written or removed; check the port and the configuration."
fi

if [ "${RECYCLED}" = true ]; then
  fail_start "PID ${PID} was reused by a different process before the health check succeeded: ${REASON}." \
             "The application is not confirmably running; no PID file was written or removed."
fi

printf 'ERROR: CM Insight (PID %s) did not answer %s within %ss: %s.\n' "${PID}" "${HEALTH_URL}" "${TIMEOUT}" "${REASON}" >&2
printf '       The process is still alive but unconfirmed, so NO PID file was written; stop it with ./bin/stop.sh --untracked, then investigate.\n' >&2
print_decisive_lines "${OUT_FILE}"
print_log_tail
exit 1
