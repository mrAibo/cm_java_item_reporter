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
#   - the PID file's process command line CANNOT be inspected -> REFUSED: this script
#     never signals a PID whose identity it cannot prove (see Ownership below)
#   - otherwise SIGTERM, wait, and with --force SIGKILL after the timeout
#
# Behaviour (no PID file, --untracked):
#   - the process that owns a listening socket serving the CONFIGURED BIND is located
#     from the platform socket table (port-scoped only: ss -ltnp or netstat -ano;
#     never a process-name scan) and is stopped, but ONLY with positive CM Insight
#     ownership evidence (see Ownership below). Without --untracked an untracked
#     instance is reported and refused instead.
#
# Ownership (Goal 01A section H - SECURITY/OPS BLOCKING):
#
#   > A process is never signalled merely because it is a JVM.
#
#   Positive evidence is one of:
#     1. the EXACT CM Insight health marker ({"status":"UP","service":"cm-insight"})
#        answered on the configured bind address, tied to the socket that is about to
#        be signalled (the marker answers on <host>:<port>, and the socket bound to
#        that address on that port is the target);
#     2. the target's command line EXPLICITLY identifies CM Insight (the
#        cm-insight.jar path, the bin/cm-insight launcher, or the
#        com.mraibo.cminsight main class) - used when health does not answer, e.g. a
#        hung or still-starting instance.
#   If neither can be established, stop.sh REFUSES, prints the sockets, the PIDs and
#   manual diagnostic commands, and signals nothing. A bare JVM identity, a 2xx from
#   some other service, a listening socket alone, or an uninspectable command line are
#   NOT evidence. No platform exception is retained: on a platform where the command
#   line cannot be read, the operator stops the process manually after checking it.
#
# Bind awareness (Goal 01A section G): the health probe and the listener match use the
# shared model in bin/lib/cm-insight-addr.sh, so a wildcard bind (0.0.0.0 / ::) is
# probed through a loopback address while a WILDCARD SOCKET is attributed to this
# configuration, and another 127/8 literal, localhost, ::1 and a specific address are
# handled at the configured address itself.
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 stopped (or was not running), 1 still running / stop failed / refused
# (untracked instance without --untracked, or ownership not proven), 2 usage error.
#
# Environment:
#   CM_INSIGHT_HOME          application home (default: the repository root); the default
#                            configuration file and the PID file live under it
#   CM_INSIGHT_CONFIG        configuration file (a relative value resolves against the home)
#   CM_INSIGHT_STOP_TIMEOUT  default graceful-stop timeout in seconds
#   CM_INSIGHT_RUN_DIR       PID file directory (default <home>/run)

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
                      (Status: UNTRACKED INSTANCE): the process that owns a listening
                      socket serving the configured bind is located and stopped
                      - but only with positive CM Insight ownership evidence
  --help              show this help

Safety: a process is never signalled merely because it is a JVM.

Tracked stops compare the PID file's process with this application and REFUSE when its
command line cannot be inspected, because ownership would then be unproven.

Untracked stops require one of:
  * the exact CM Insight health marker ({"status":"UP","service":"cm-insight"})
    answering on the configured bind, on the socket that will be signalled; or
  * a command line that explicitly identifies CM Insight (cm-insight.jar,
    bin/cm-insight, or com.mraibo.cminsight) when health does not answer.
If neither holds, stop.sh refuses, prints manual diagnostic instructions and signals
nothing: an unrelated Tomcat/WAS/Java process on the configured port is never killed.
There is no platform exception and no "assume anyway" switch.

Environment:
  CM_INSIGHT_HOME    application home (default: the repository root); the default
                     configuration file and the PID file live under it
  CM_INSIGHT_CONFIG  configuration file (default <home>/conf/application.properties;
                     a relative value resolves against the application home)
  CM_INSIGHT_STOP_TIMEOUT  default graceful-stop timeout in seconds

Exit codes: 0 stopped or not running, 1 still running / stop failed / refused
(owned by someone else, or ownership not proven), 2 usage error.
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

LIB_DIR="${SCRIPT_DIR}/lib"
for module in cm-insight-addr.sh cm-insight-lifecycle.sh; do
  [ -r "${LIB_DIR}/${module}" ] || { printf 'ERROR: required module is missing: %s\n' "${LIB_DIR}/${module}" >&2; exit 2; }
done
# shellcheck source=/dev/null
. "${LIB_DIR}/cm-insight-lifecycle.sh"

# One path rule (Goal 01A section D), shared with bin/cm-insight: the application home
# is CM_INSIGHT_HOME when set, else the repository root, and the default config file
# and the PID file live under it.
APP_HOME="$(ci_effective_home "${ROOT}")"

RUN_DIR="${CM_INSIGHT_RUN_DIR:-${APP_HOME}/run}"
PID_FILE="${RUN_DIR}/cm-insight.pid"
CONFIG="$(ci_effective_config "${APP_HOME}")"

BIND="$(ci_effective_bind "${CONFIG}")"
PORT="$(ci_effective_port "${CONFIG}")"
BIND_DISPLAY="$(ci_bind_display "${BIND}" "${PORT}")"

log_ok() { printf 'OK: %s\n' "$*"; }
log_warn() { printf 'WARN: %s\n' "$*" >&2; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

wait_for_exit() {
  # $1 = pid, $2 = seconds; true when the process is gone from BOTH nets (see
  # ci_process_exists: a native process can leave the shell pid space while it lives)
  _pid="$1"
  _left="$2"
  while [ "${_left}" -gt 0 ]; do
    if ! ci_process_exists "${_pid}"; then return 0; fi
    sleep 1
    _left=$((_left - 1))
  done
  if ! ci_process_exists "${_pid}"; then return 0; fi
  return 1
}

rows_pids() {
  # $1 = rows ("host|pid|class"); prints the distinct numeric PIDs, one per line.
  printf '%s\n' "$1" | awk -F'|' 'NF>=3 && $2 ~ /^[0-9]+$/ {print $2}' | sort -u
}

rows_count() {
  # stdin = newline separated list; prints how many non-empty lines it has
  grep -c '[^[:space:]]' || true
}

print_manual_diagnostics() {
  # Never a guess: tell the operator exactly how to identify and stop a process by hand.
  printf '       Manual diagnostics (nothing was signalled):\n' >&2
  printf '         Linux : ss -ltnp | grep %s      (owner pid of the listening socket)\n' "${PORT}" >&2
  printf '         Linux : ps -p <pid> -o pid=,args=\n' >&2
  printf '         Windows/PowerShell : Get-NetTCPConnection -LocalPort %s -State Listen | Select-Object LocalAddress,OwningProcess\n' "${PORT}" >&2
  printf '         Windows/PowerShell : Get-CimInstance Win32_Process -Filter "ProcessId=<pid>" | Select-Object ProcessId,CommandLine\n' >&2
  printf '         Then stop it by hand only after the command line names CM Insight:\n' >&2
  printf '           taskkill /PID <pid>   or   kill <pid>\n' >&2
  printf '       Expected CM Insight evidence: the exact health body {"status":"UP","service":"cm-insight"}\n' >&2
  printf '       on %s, or a command line containing cm-insight.jar / bin/cm-insight / com.mraibo.cminsight.\n' "$(ci_bind_display "${BIND}" "${PORT}")" >&2
}

print_sockets_on_port() {
  # Full, unfiltered socket report for the configured port (honest evidence).
  printf '       Listening sockets on port %s that could serve %s:\n' "${PORT}" "${BIND}" >&2
  _any=false
  while IFS='|' read -r _host _pid _cls; do
    [ -n "${_host}" ] || continue
    _any=true
    case "${_pid}" in ''|-)
      printf '         %s %s (owner PID unknown on this platform)\n' "${_cls}" "$(ci_describe_socket "${_host}" "${_cls}" "${BIND}" "${PORT}")" >&2
      ;;
    *)
      printf '         %s %s (owner PID %s)\n' "${_cls}" "$(ci_describe_socket "${_host}" "${_cls}" "${BIND}" "${PORT}")" "${_pid}" >&2
      ;;
    esac
  done < <(ci_bind_listener_rows "${PORT}" "${BIND}")
  if [ "${_any}" = false ]; then
    printf '         (no listening socket on port %s was found in the platform socket table)\n' "${PORT}" >&2
  fi
}

# ===========================================================================
# no PID file: tracked state is absent -> the --untracked path (ownership first)
# ===========================================================================
stop_untracked_flow() {
  # The no-usable-PID-file path: find the socket that serves the configured bind and
  # stop it ONLY with positive CM Insight ownership evidence. Called when there is no
  # PID file at all and, with --untracked, when the PID file turned out to be stale or
  # unresolvable while an instance still answers (review finding F2).
  if [ "${PORT}" = "0" ]; then
    printf 'ERROR: web.port=0 binds a random free port, so no listener can be attributed to this configuration; refusing to guess.\n' >&2
    printf '       The PID file %s tracks a normal start; without it, identify the process manually.\n' "${PID_FILE}" >&2
    print_manual_diagnostics
    exit 1
  fi

  ci_health_probe "${PORT}" "${BIND}"

  # Rows that could be this configuration's listener (exact before covered).
  SERVING_ROWS=""
  while IFS='|' read -r _host _pid _cls; do
    [ -n "${_host}" ] || continue
    case "${_cls}" in foreign|sibling) continue ;; esac
    SERVING_ROWS="${SERVING_ROWS}${_host}|${_pid}|${_cls}"$'\n'
  done < <(ci_bind_listener_rows "${PORT}" "${BIND}")
  SERVING_ROWS="${SERVING_ROWS%$'\n'}"

  if [ -z "${SERVING_ROWS}" ]; then
    if [ "${CI_HEALTH_KIND}" = "our" ]; then
      printf 'ERROR: an instance answers %s with the CM Insight marker, but the socket that accepted it could not be attributed to a process on this platform; refusing to guess.\n' "${CI_HEALTH_URL}" >&2
      print_sockets_on_port
      print_manual_diagnostics
      exit 1
    fi
    if [ "${CI_HEALTH_KIND}" = "foreign" ]; then
      log_ok "CM Insight is not running: a different service holds ${CI_HEALTH_URL} (HTTP ${CI_HEALTH_CODE}, no cm-insight marker); nothing was signalled"
    elif [ -n "$(ci_bind_listener_rows "${PORT}" "${BIND}" | awk -F'|' '$3=="foreign" || $3=="sibling"')" ]; then
      log_ok "CM Insight is not running (no PID file ${PID_FILE}); port ${PORT} is held at another address or in the other address family, and nothing was signalled"
    else
      log_ok "CM Insight is not running (no PID file ${PID_FILE})"
    fi
    exit 0
  fi

  # -------------------------------------------------------------------------
  # Ownership evidence
  # -------------------------------------------------------------------------
  TARGET_ROWS="${SERVING_ROWS}"
  MARKER_TIED=false
  if [ "${CI_HEALTH_KIND}" = "our" ]; then
    _tied="$(printf '%s\n' "${TARGET_ROWS}" | awk -F'|' -v h="${CI_HEALTH_HOST}" '$1==h')"
    if [ -n "${_tied}" ]; then
      # The marker answered at CI_HEALTH_HOST:PORT, so the socket bound to that exact
      # address is the only one that can have accepted it.
      TARGET_ROWS="${_tied}"
      MARKER_TIED=true
    elif [ "$(printf '%s\n' "${TARGET_ROWS}" | awk -F'|' '$1=="0.0.0.0" || $1=="::" || $1=="*"' | rows_count)" = "$(printf '%s\n' "${TARGET_ROWS}" | rows_count)" ]; then
      # Every socket that could serve the bind is a wildcard socket, and the marker
      # answered on a loopback probe address that only such a socket can accept, so the
      # wildcard socket is the one that answered.
      MARKER_TIED=true
    fi
  fi

  _pids="$(rows_pids "${TARGET_ROWS}")"
  _pid_count="$(printf '%s\n' "${_pids}" | rows_count)"
  if [ "${_pid_count}" -gt 1 ]; then
    # Several distinct processes serve the configured bind: keep only those whose
    # command line explicitly identifies CM Insight, and refuse if that is not exactly one.
    _identified=""
    while IFS='|' read -r _h _p _c; do
      [ -n "${_h}" ] || continue
      case "${_p}" in ''|-|*[!0-9]*) continue ;; esac
      if ci_proc_identity "${_p}"; then
        _identified="${_identified}${_h}|${_p}|${_c}"$'\n'
      fi
    done <<< "${TARGET_ROWS}"
    if [ -n "${_identified}" ]; then
      TARGET_ROWS="${_identified%$'\n'}"
      _pids="$(rows_pids "${TARGET_ROWS}")"
      _pid_count="$(printf '%s\n' "${_pids}" | rows_count)"
    fi
  fi

  if [ "${_pid_count}" -eq 0 ]; then
    printf 'ERROR: %s is bound, but the owner PID could not be resolved on this platform; refusing to guess.\n' "${BIND_DISPLAY}" >&2
    print_sockets_on_port
    print_manual_diagnostics
    exit 1
  fi
  if [ "${_pid_count}" -gt 1 ]; then
    printf 'ERROR: %s distinct processes serve %s (PIDs: %s); refusing to guess which one to signal.\n' "${_pid_count}" "${BIND_DISPLAY}" "$(printf '%s' "${_pids}" | tr '\n' ' ')" >&2
    print_sockets_on_port
    print_manual_diagnostics
    exit 1
  fi

  TARGET_PID="${_pids}"
  TARGET_ROW="$(printf '%s\n' "${TARGET_ROWS}" | awk -F'|' -v p="${TARGET_PID}" '$2==p {print; exit}')"
  TARGET_HOST="${TARGET_ROW%%|*}"
  TARGET_REST="${TARGET_ROW#*|}"
  TARGET_CLASS="${TARGET_REST##*|}"

  EVIDENCE=""
  if [ "${MARKER_TIED}" = true ]; then
    EVIDENCE="the exact CM Insight health marker answered at ${CI_HEALTH_URL}, on the socket $(ci_describe_socket "${TARGET_HOST}" "${TARGET_CLASS}" "${BIND}" "${PORT}")"
  fi

  set +e
  ci_proc_identity "${TARGET_PID}"
  IDENT_RC=$?
  set -e
  if [ "${IDENT_RC}" -eq 0 ]; then
    if [ -n "${EVIDENCE}" ]; then
      EVIDENCE="${EVIDENCE}, and its command line also names CM Insight"
    else
      EVIDENCE="the command line of PID ${TARGET_PID} explicitly names CM Insight (cm-insight.jar / bin/cm-insight / com.mraibo.cminsight)"
    fi
  fi

  if [ -z "${EVIDENCE}" ]; then
    # No proof of ownership. The old behaviour killed any JVM here; this refuses.
    if [ "${UNTRACKED}" != true ]; then
      if [ "${CI_HEALTH_KIND}" = "foreign" ]; then
        log_ok "CM Insight is not running: a different service holds ${CI_HEALTH_URL} (HTTP ${CI_HEALTH_CODE}, no cm-insight marker); nothing was signalled"
      elif [ "${IDENT_RC}" -eq 1 ]; then
        log_ok "CM Insight is not running: PID ${TARGET_PID} owns $(ci_bind_display "${TARGET_HOST}" "${PORT}") but its command line does not name CM Insight; nothing was signalled"
      else
        log_ok "CM Insight is not running (no PID file ${PID_FILE}); nothing was signalled"
      fi
      exit 0
    fi
    printf 'ERROR: REFUSING to signal PID %s: no CM Insight ownership evidence could be established.\n' "${TARGET_PID}" >&2
    case "${IDENT_RC}" in
      1) printf '       Its command line does NOT name CM Insight (no cm-insight.jar, no bin/cm-insight, no com.mraibo.cminsight),\n' >&2
         printf '       so it is a different service. A process is never signalled merely because it is a JVM.\n' >&2
         ;;
      *) printf '       Its command line could not be inspected on this platform, so identity cannot be checked either.\n' >&2
         ;;
    esac
    printf '       The exact CM Insight marker did not answer on %s (probe result: %s, HTTP %s).\n' "${BIND_DISPLAY}" "${CI_HEALTH_KIND}" "${CI_HEALTH_CODE}" >&2
    print_sockets_on_port
    print_manual_diagnostics
    exit 1
  fi

  if [ "${UNTRACKED}" != true ]; then
    printf 'ERROR: an untracked instance is running on %s and no PID file tracks it (%s); refusing to stop it without --untracked.\n' "${BIND_DISPLAY}" "${PID_FILE}" >&2
    printf '       Ownership evidence found: %s.\n' "${EVIDENCE}" >&2
    printf '       Stop it explicitly with ./bin/stop.sh --untracked (add --force to escalate), or inspect it with ./bin/status.sh.\n' >&2
    exit 1
  fi

  # The socket must still be owned by the same PID right now (a PID can be recycled
  # between the query and the signal).
  if ! ci_bind_listener_rows "${PORT}" "${BIND}" | awk -F'|' -v p="${TARGET_PID}" '$2==p && $3!="foreign"' | grep -q .; then
    fail "PID ${TARGET_PID} no longer owns a socket on ${BIND_DISPLAY}; nothing was signalled (re-run stop.sh --untracked)"
  fi

  SIG_PID="$(ci_signalable_pid "${TARGET_PID}")" || fail "PID ${TARGET_PID} owns a socket on ${BIND_DISPLAY} but cannot be resolved into a signalable local process; nothing was signalled"
  log_warn "untracked instance: ownership proven by ${EVIDENCE}"
  log_ok "stopping the UNTRACKED instance (PID ${TARGET_PID}, socket-table pid; signal target ${SIG_PID}) with SIGTERM; waiting up to ${TIMEOUT}s"
  if ! kill -TERM "${SIG_PID}" 2>/dev/null; then
    if ci_process_exists "${SIG_PID}"; then
      fail "cannot send SIGTERM to PID ${SIG_PID}; check permissions (are you the owner of the process?)"
    fi
  fi

  if ! wait_for_exit "${SIG_PID}" "${TIMEOUT}"; then
    if [ "${FORCE}" = true ]; then
      log_warn "graceful stop timed out after ${TIMEOUT}s; sending SIGKILL to PID ${SIG_PID}"
      kill -KILL "${SIG_PID}" 2>/dev/null || true
      if ci_process_exists "${SIG_PID}" && command -v taskkill >/dev/null 2>&1; then
        taskkill //F //PID "${TARGET_PID}" >/dev/null 2>&1 || true
      fi
      wait_for_exit "${SIG_PID}" 10 || fail "PID ${SIG_PID} is still present after SIGKILL"
    else
      printf 'ERROR: the untracked instance (PID %s) is still running after %ss; retry with ./bin/stop.sh --untracked --force\n' "${SIG_PID}" "${TIMEOUT}" >&2
      exit 1
    fi
  fi

  ci_health_probe "${PORT}" "${BIND}"
  if [ "${CI_HEALTH_KIND}" = "our" ]; then
    fail "the untracked instance still answers ${CI_HEALTH_URL} after stopping PID ${SIG_PID}; it may have been restarted (check ./bin/status.sh)"
  fi
  log_ok "the untracked instance is stopped and ${BIND_DISPLAY} no longer answers as CM Insight"
  exit 0
}

if [ ! -f "${PID_FILE}" ]; then
  stop_untracked_flow
fi

# ===========================================================================
# tracked instance (PID file)
# ===========================================================================
PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
PID="${PID%%$'\n'*}"

stale_pid_file_exit() {
  # $1 = reason. The PID file is unusable (invalid, dead, foreign, or unresolvable), so
  # it is removed. The marker is then probed FIRST (review finding F2): if an instance
  # still answers the exact CM Insight marker while the PID file could not be used,
  # "CM Insight is not running" would be a false report - the process is running and is
  # merely UNTRACKED - so this never exits 0 in that state:
  #   * with --untracked the explicit recovery path runs (same ownership-proof rules);
  #   * without it, the operator is told to use --untracked and the exit code is 1.
  log_warn "$1"
  rm -f "${PID_FILE}"
  ci_health_probe "${PORT}" "${BIND}"
  if [ "${CI_HEALTH_KIND}" = "our" ]; then
    log_warn "an instance still answers ${CI_HEALTH_URL} while ${PID_FILE} was unusable: that is an UNTRACKED INSTANCE, and it is still RUNNING"
    if [ "${UNTRACKED}" = true ]; then
      log_warn "recovering through the --untracked ownership-proof path"
      stop_untracked_flow
    fi
    printf 'ERROR: refusing to report "not running": an instance answers %s but no usable PID file tracks it (%s was removed).\n' "${CI_HEALTH_URL}" "${PID_FILE}" >&2
    printf '       Recover with ./bin/stop.sh --untracked (ownership is proven before any signal), or inspect it with ./bin/status.sh.\n' >&2
    exit 1
  fi
  if [ "${CI_HEALTH_KIND}" = "foreign" ]; then
    log_ok "CM Insight is not running: a different service holds ${CI_HEALTH_URL} (HTTP ${CI_HEALTH_CODE}, no cm-insight marker); nothing was signalled"
    exit 0
  fi
  log_ok "CM Insight is not running"
  exit 0
}

case "${PID}" in
  ''|*[!0-9]*)
    stale_pid_file_exit "PID file ${PID_FILE} does not contain a valid PID ('${PID}'); removing the stale file"
    ;;
esac

if ! ci_pid_is_running "${PID}" "${PORT}" "${BIND}"; then
  # Not in the process table (either namespace) and not the owner of a serving socket.
  stale_pid_file_exit "PID ${PID} from ${PID_FILE} is not running (absent from the process table and from the socket table); not signalling anything and removing the stale PID file"
fi

set +e
ci_proc_identity "${PID}"
IDENT_RC=$?
set -e

if [ "${IDENT_RC}" -eq 1 ]; then
  stale_pid_file_exit "PID ${PID} from ${PID_FILE} is a different process; not signalling it and removing the stale PID file"
fi

if [ "${IDENT_RC}" -eq 2 ]; then
  # Fail-safe (Goal 01A section H): without a readable command line the PID file alone
  # is not proof. The one piece of evidence that can still tie the PID to this
  # application is the CM Insight marker answering on a socket this PID owns.
  TARGET_ROWS=""
  while IFS='|' read -r _h _p _c; do
    [ -n "${_h}" ] || continue
    [ "${_c}" = "foreign" ] && continue
    TARGET_ROWS="${TARGET_ROWS}${_h}|${_p}|${_c}"$'\n'
  done < <(ci_bind_listener_rows "${PORT}" "${BIND}")
  TARGET_ROWS="${TARGET_ROWS%$'\n'}"
  ci_health_probe "${PORT}" "${BIND}"
  OWNED_BY_TRACKED=false
  if [ -n "${TARGET_ROWS}" ]; then
    while IFS='|' read -r _h _p _c; do
      [ "${_c}" = "foreign" ] && continue
      ci_same_process "${_p}" "${PID}" || continue
      if [ "${CI_HEALTH_KIND}" = "our" ] && [ "${CI_HEALTH_HOST}" = "${_h}" ]; then
        OWNED_BY_TRACKED=true
      elif [ "${CI_HEALTH_KIND}" = "our" ] && [ "${_c}" = "covered" ] && [ "$(rows_pids "${TARGET_ROWS}" | rows_count)" = "1" ]; then
        OWNED_BY_TRACKED=true
      fi
    done <<< "${TARGET_ROWS}"
  fi
  if [ "${OWNED_BY_TRACKED}" != true ]; then
    printf 'ERROR: refusing to signal PID %s: its command line cannot be inspected on this platform, so it is not proven to be CM Insight.\n' "${PID}" >&2
    printf '       The only acceptable evidence in that state is the exact CM Insight marker on a socket this PID owns, and it was not found.\n' >&2
    printf '       The PID file was kept: %s still tracks PID %s as UNPROVEN.\n' "${PID_FILE}" "${PID}" >&2
    print_sockets_on_port
    print_manual_diagnostics
    exit 1
  fi
  log_warn "the command line of PID ${PID} cannot be inspected on this platform; proceeding because the exact CM Insight marker answered at ${CI_HEALTH_URL} on a socket this PID owns"
fi

log_ok "stopping CM Insight (PID ${PID}) with SIGTERM; waiting up to ${TIMEOUT}s"
# The signalable target is resolved explicitly (review finding H-4): under MSYS the PID
# file normally holds the shell pid, but a PID file written in the platform namespace
# must be mapped before `kill` may see it - an unmapped number would name whatever local
# process happens to carry it. An unresolvable target is refused, never guessed.
SIG_PID="$(ci_signalable_pid "${PID}")" || {
  printf 'ERROR: refusing to signal PID %s: it is alive but cannot be resolved into a signalable local process; nothing was signalled.\n' "${PID}" >&2
  printf '       The PID file was kept: %s still tracks PID %s.\n' "${PID_FILE}" "${PID}" >&2
  print_sockets_on_port
  print_manual_diagnostics
  exit 1
}
if ! kill -TERM "${SIG_PID}" 2>/dev/null; then
  if ci_process_exists "${SIG_PID}"; then
    fail "cannot send SIGTERM to PID ${SIG_PID}; check permissions (are you the owner of the process?)"
  fi
  rm -f "${PID_FILE}"
  log_ok "CM Insight stopped"
  exit 0
fi

if wait_for_exit "${SIG_PID}" "${TIMEOUT}"; then
  rm -f "${PID_FILE}"
  log_ok "CM Insight stopped (PID ${PID}${SIG_PID:+; signal target ${SIG_PID}})"
  exit 0
fi

if [ "${FORCE}" = true ]; then
  set +e
  ci_proc_identity "${PID}"
  IDENT_RC=$?
  set -e
  if [ "${IDENT_RC}" -eq 1 ]; then
    log_warn "PID ${PID} was reused by a different process while waiting; not sending SIGKILL and removing the stale PID file"
    rm -f "${PID_FILE}"
    log_ok "CM Insight is not running"
    exit 0
  fi
  if [ "${IDENT_RC}" -eq 2 ]; then
    printf 'ERROR: refusing to send SIGKILL to PID %s: its command line can no longer be inspected, so it is not proven to be CM Insight.\n' "${PID}" >&2
    printf '       A moment ago it was identified as CM Insight; re-run ./bin/stop.sh to re-check, or stop it manually after verifying its command line.\n' >&2
    exit 1
  fi
  log_warn "graceful stop timed out after ${TIMEOUT}s; sending SIGKILL to PID ${SIG_PID}"
  if ! kill -KILL "${SIG_PID}" 2>/dev/null; then
    if ci_process_exists "${SIG_PID}"; then fail "cannot send SIGKILL to PID ${SIG_PID}; check permissions"; fi
  fi
  if wait_for_exit "${SIG_PID}" 10; then
    rm -f "${PID_FILE}"
    log_ok "CM Insight stopped (SIGKILL, PID ${PID})"
    exit 0
  fi
  fail "PID ${SIG_PID} is still present after SIGKILL; the PID file was kept for diagnosis"
fi

printf 'ERROR: CM Insight (PID %s) is still running after %ss. Review %s, then retry with ./bin/stop.sh --force\n' "${PID}" "${TIMEOUT}" "${ROOT}/logs/cm-insight.out" >&2
exit 1
