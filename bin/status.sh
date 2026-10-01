#!/usr/bin/env bash
#
# CM Insight - status report.
#
#   ./bin/status.sh [--help]
#
# States:
#   RUNNING             PID file holds a live CM Insight process (health also
#                       checked on the configured bind when curl is available)
#   UNTRACKED INSTANCE  no PID file, but the configured bind answers the exact
#                       CM Insight health marker: an instance is running and is NOT
#                       tracked by run/cm-insight.pid
#   UNREACHABLE         the process is alive but the configured bind does not answer
#                       with the CM Insight marker
#   STALE PID FILE      the PID file is invalid, dead, or owned by an unrelated process
#   STOPPED             no PID file and nothing answers on the configured address
#
# Bind awareness (Goal 01A section G):
#   the shared address model in bin/lib decides where the health probe dials and which
#   sockets count as this configuration's listener. A wildcard bind (0.0.0.0 / ::) is
#   probed through a loopback address (127.0.0.1 / ::1) and a wildcard socket is
#   reported as the listener that serves the configured bind; a specific address,
#   another 127/8 literal, localhost and ::1 are probed at the configured address.
#   Health is only ever "OK" for the exact {"status":"UP","service":"cm-insight"}
#   marker, never for a bare HTTP 2xx from a foreign service on the port.
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 RUNNING + healthy, 3 STOPPED, 1 STALE PID FILE / UNTRACKED /
# UNREACHABLE.
# Environment:
#   CM_INSIGHT_HOME        application home (default: the repository root); the default
#                          configuration file, the PID file and the log live under it
#   CM_INSIGHT_CONFIG      configuration file (a relative value resolves against the home)
#   CM_INSIGHT_RUN_DIR     PID file directory (default <home>/run)

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./bin/status.sh [--help]

Reports the local state of CM Insight:
  Status: RUNNING / STOPPED / STALE PID FILE / UNTRACKED INSTANCE / UNREACHABLE
  Health: OK / UNREACHABLE / SKIPPED

UNTRACKED INSTANCE means an instance answers the configured bind address but no
run/cm-insight.pid tracks it (for example the PID file was removed while it ran, or a
previous start failed to publish it). Recover with ./bin/stop.sh --untracked; the stop
itself requires positive CM Insight ownership evidence (the health marker, or a command
line that names CM Insight) and refuses to signal anything else.

Environment:
  CM_INSIGHT_HOME     application home (default: the repository root); the default
                      configuration file, the PID file and the log live under it
  CM_INSIGHT_CONFIG   configuration file (default <home>/conf/application.properties;
                      a relative value resolves against the application home)

The health check targets the configured web.bind: a loopback literal is probed there,
localhost via 127.0.0.1 (then ::1), ::1 via [::1], a wildcard bind through 127.0.0.1
(or ::1 for "::"), and a specific address verbatim. With web.port=0 the port is
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

LIB_DIR="${SCRIPT_DIR}/lib"
for module in cm-insight-addr.sh cm-insight-lifecycle.sh; do
  [ -r "${LIB_DIR}/${module}" ] || { printf 'ERROR: required module is missing: %s\n' "${LIB_DIR}/${module}" >&2; exit 2; }
done
# shellcheck source=/dev/null
. "${LIB_DIR}/cm-insight-lifecycle.sh"

# One path rule (Goal 01A section D), shared with bin/cm-insight: the application home
# is CM_INSIGHT_HOME when set, else the repository root, and the default config file,
# the PID file and the logs live under it.
APP_HOME="$(ci_effective_home "${ROOT}")"

RUN_DIR="${CM_INSIGHT_RUN_DIR:-${APP_HOME}/run}"
LOG_DIR="${CM_INSIGHT_LOG_DIR:-${APP_HOME}/logs}"
PID_FILE="${RUN_DIR}/cm-insight.pid"
OUT_FILE="${LOG_DIR}/cm-insight.out"
CONFIG="$(ci_effective_config "${APP_HOME}")"

BIND="$(ci_effective_bind "${CONFIG}")"
PORT="$(ci_effective_port "${CONFIG}")"
BIND_DISPLAY="$(ci_bind_display "${BIND}" "${PORT}")"
PROBE_HOST="$(ci_addr_probe_host "${BIND}")"
HEALTH_URL="$(ci_health_url "${PROBE_HOST}" "${PORT}")"
BIND_LOOPBACK=false
ci_addr_is_loopback "${BIND}" && BIND_LOOPBACK=true

print_bind_line() { printf 'Bind:   %s (%s)\n' "${BIND_DISPLAY}" "$(ci_describe_bind "${BIND}")"; }

SERVING_HOST=""
SERVING_PID=""
SERVING_CLASS=""
find_serving_listener() {
  # Sets SERVING_HOST/SERVING_PID/SERVING_CLASS to the socket that best represents the
  # configuration: the socket bound to the configured address itself first, then any
  # other exact form, then a wildcard socket that covers it. Runs in the current shell
  # (process substitution, not a pipeline) so the caller can use the values.
  SERVING_HOST=""
  SERVING_PID=""
  SERVING_CLASS=""
  local _pass _cls _host _pid _class
  for _pass in configured exact covered; do
    while IFS='|' read -r _host _pid _class; do
      _cls="${_class}"
      case "${_cls}" in exact|covered) : ;; *) continue ;; esac
      case "${_pass}" in
        configured) [ "${_host}" = "${BIND}" ] || continue ;;
        exact) [ "${_cls}" = "exact" ] || continue ;;
        covered) [ "${_cls}" = "covered" ] || continue ;;
      esac
      SERVING_HOST="${_host}"
      SERVING_PID="${_pid}"
      SERVING_CLASS="${_class}"
      return 0
    done < <(ci_bind_listener_rows "${PORT}" "${BIND}")
  done
  return 0
}

print_serving_listener() {
  # prints the resolved serving socket as one "Listener:" line; nothing when absent.
  [ -n "${SERVING_HOST}" ] || return 0
  case "${SERVING_PID}" in
    ''|-)
      printf 'Listener: %s (owner PID could not be resolved on this platform)\n' "$(ci_describe_socket "${SERVING_HOST}" "${SERVING_CLASS}" "${BIND}" "${PORT}")"
      ;;
    *)
      printf 'Listener: %s (owner PID %s)\n' "$(ci_describe_socket "${SERVING_HOST}" "${SERVING_CLASS}" "${BIND}" "${PORT}")" "${SERVING_PID}"
      ;;
  esac
}

print_foreign_listeners() {
  # WARN lines for sockets on the configured port that are NOT this configuration's
  # listener: another address on the same port (foreign), or the other address family's
  # wildcard on the same port (sibling). Neither is ever a signalling target.
  ci_bind_listener_rows "${PORT}" "${BIND}" | while IFS='|' read -r _host _pid _class; do
    case "${_class}" in foreign) _why="is held by another service" ;; sibling) _why="is held by a socket of the other address family" ;; *) continue ;; esac
    case "${_pid}" in ''|-)
      printf 'WARN:   %s %s (owner PID unknown)\n' "$(ci_bind_display "${_host}" "${PORT}")" "${_why}" >&2
      ;;
    *)
      printf 'WARN:   %s %s (owner PID %s)\n' "$(ci_bind_display "${_host}" "${PORT}")" "${_why}" "${_pid}" >&2
      ;;
    esac
  done
}

print_tracked_listener() {
  # $1 = the PID from the PID file. Prints the socket serving the configured bind and
  # whether the tracked process is its owner. The socket-table pid is a PLATFORM pid
  # (a Windows pid under MSYS, an MSYS pid in the PID file), so the two are compared
  # through ci_same_process instead of by number.
  [ -n "${SERVING_HOST}" ] || return 0
  case "${SERVING_PID}" in
    ''|-)
      printf 'Listener: %s (owner PID could not be resolved on this platform)\n' "$(ci_describe_socket "${SERVING_HOST}" "${SERVING_CLASS}" "${BIND}" "${PORT}")"
      ;;
    *)
      if ci_same_process "${SERVING_PID}" "$1"; then
        printf 'Listener: %s (owner PID %s, the tracked process)\n' "$(ci_describe_socket "${SERVING_HOST}" "${SERVING_CLASS}" "${BIND}" "${PORT}")" "${SERVING_PID}"
      else
        printf 'Listener: %s (owner PID %s)\n' "$(ci_describe_socket "${SERVING_HOST}" "${SERVING_CLASS}" "${BIND}" "${PORT}")" "${SERVING_PID}"
        printf 'WARN:   the socket serving %s is NOT owned by the tracked PID %s; the instance may have been restarted (stop.sh never signals a process it cannot prove)\n' "${BIND_DISPLAY}" "$1" >&2
      fi
      ;;
  esac
}

print_running_header() {
  # $1 = the PID from the PID file
  printf 'Status: RUNNING\n'
  printf 'PID:    %s\n' "$1"
  print_bind_line
  print_tracked_listener "$1"
  printf 'Log:    %s\n' "${OUT_FILE}"
  if [ "${IDENT_RC}" -eq 2 ]; then
    printf 'WARN:   the command line of PID %s could not be inspected on this platform, so it is not proven to be CM Insight (%s)\n' "$1" "${PID_FILE}" >&2
  fi
}

ci_health_probe "${PORT}" "${BIND}"
find_serving_listener

if [ ! -f "${PID_FILE}" ]; then
  case "${CI_HEALTH_KIND}" in
    our)
      printf 'Status: UNTRACKED INSTANCE\n'
      printf 'Detail: an instance answers %s but no PID file tracks it (%s)\n' "${CI_HEALTH_URL}" "${PID_FILE}"
      print_bind_line
      print_serving_listener
      printf 'Health: OK (%s)\n' "${CI_HEALTH_URL}"
      printf 'Fix:    ./bin/stop.sh --untracked stops it (ownership is proven first); ./bin/start.sh refuses to add a second one\n'
      exit 1
      ;;
    foreign)
      printf 'Status: STOPPED\n'
      printf 'PID:    none (%s)\n' "${PID_FILE}"
      print_bind_line
      printf 'WARN:   %s answers HTTP %s but not the cm-insight health marker; another service holds the port\n' "${CI_HEALTH_URL}" "${CI_HEALTH_CODE}" >&2
      print_foreign_listeners
      exit 3
      ;;
    *)
      printf 'Status: STOPPED\n'
      printf 'PID:    none (%s)\n' "${PID_FILE}"
      print_bind_line
      if [ -n "${SERVING_HOST}" ]; then
        case "${SERVING_PID}" in
          ''|-)
            printf 'WARN:   %s is bound (%s) but does not answer as cm-insight: another service, or a hung/starting instance that is not tracked\n' "${BIND_DISPLAY}" "$(ci_describe_socket "${SERVING_HOST}" "${SERVING_CLASS}" "${BIND}" "${PORT}")" >&2
            ;;
          *)
            printf 'WARN:   %s is bound (%s, owner PID %s) but does not answer as cm-insight: another service, or a hung/starting instance that is not tracked\n' "${BIND_DISPLAY}" "$(ci_describe_socket "${SERVING_HOST}" "${SERVING_CLASS}" "${BIND}" "${PORT}")" "${SERVING_PID}" >&2
            ;;
        esac
        printf 'WARN:   inspect it and, only if it is CM Insight, recover with ./bin/stop.sh --untracked (ownership is proven before any signal)\n' >&2
      else
        print_foreign_listeners
      fi
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
    print_bind_line
    printf 'Fix:    ./bin/stop.sh removes it, then ./bin/start.sh\n'
    exit 1
    ;;
esac

if ! ci_pid_is_running "${PID}" "${PORT}" "${BIND}"; then
  printf 'Status: STALE PID FILE\n'
  printf 'PID:    %s (not running: absent from the process table and from the socket table)\n' "${PID}"
  print_bind_line
  printf 'Fix:    ./bin/stop.sh removes it, then ./bin/start.sh\n'
  if [ "${CI_HEALTH_KIND}" = "our" ]; then
    printf 'WARN:   %s still answers the CM Insight marker, so an UNTRACKED INSTANCE is running; stop it with ./bin/stop.sh --untracked\n' "${CI_HEALTH_URL}" >&2
  fi
  exit 1
fi

set +e
ci_proc_identity "${PID}"
IDENT_RC=$?
set -e
if [ "${IDENT_RC}" -eq 1 ]; then
  printf 'Status: STALE PID FILE\n'
  printf 'PID:    %s is a different process (recycled PID)\n' "${PID}"
  print_bind_line
  printf 'Fix:    ./bin/stop.sh removes the stale PID file, then ./bin/start.sh\n'
  if [ "${CI_HEALTH_KIND}" = "our" ]; then
    printf 'WARN:   %s still answers the CM Insight marker, so an UNTRACKED INSTANCE is running; stop it with ./bin/stop.sh --untracked\n' "${CI_HEALTH_URL}" >&2
  fi
  exit 1
fi

if [ "${PORT}" = "0" ]; then
  print_running_header "${PID}"
  printf 'Health: SKIPPED (web.port=0 binds a random free port; use a fixed port for health probing)\n'
  exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
  print_running_header "${PID}"
  printf 'Health: SKIPPED (curl is not available; process existence only)\n'
  exit 0
fi

case "${CI_HEALTH_KIND}" in
  our)
    print_running_header "${PID}"
    printf 'Health: OK (%s)\n' "${CI_HEALTH_URL}"
    exit 0
    ;;
  foreign)
    printf 'Status: UNREACHABLE\n'
    printf 'PID:    %s (alive)\n' "${PID}"
    print_bind_line
    printf 'Log:    %s\n' "${OUT_FILE}"
    printf 'Health: %s answered HTTP %s but NOT the cm-insight marker; another service may hold the port\n' "${CI_HEALTH_URL}" "${CI_HEALTH_CODE}"
    printf 'Fix:    check %s; a foreign service on the port is never reported as CM Insight\n' "${OUT_FILE}"
    exit 1
    ;;
  *)
    printf 'Status: UNREACHABLE\n'
    printf 'PID:    %s (alive)\n' "${PID}"
    print_bind_line
    printf 'Log:    %s\n' "${OUT_FILE}"
    printf 'Health: no answer from %s within 2s (the process is alive)\n' "${HEALTH_URL}"
    if [ "${BIND_LOOPBACK}" != true ]; then
      printf 'Fix:    check %s; a non-loopback bind additionally needs web.allowInsecureHttp=true and a configured password\n' "${OUT_FILE}"
    else
      printf 'Fix:    check %s\n' "${OUT_FILE}"
    fi
    exit 1
    ;;
esac
