#!/usr/bin/env bash
#
# CM Insight - start the application detached, verify it is really up.
#
#   ./bin/start.sh [--timeout SECONDS] [--help] [-- application arguments...]
#
# What it does:
#   1. reads run/cm-insight.pid; a valid, live, CM Insight PID short-circuits as
#      "already running" and a stale/foreign PID file is cleaned up
#   2. refuses to start when the CONFIGURED BIND ADDRESS already answers the exact
#      CM Insight health marker but no PID file tracks it (an UNTRACKED INSTANCE),
#      and also refuses when another service holds the configured port or the
#      socket that would serve the configured address
#   3. runs bin/doctor.sh as a pre-flight check
#   4. launches bin/cm-insight with nohup, appending to logs/cm-insight.out
#   5. health-checks the configured bind until the timeout, verifying on every
#      iteration that the PID is still our process AND that the answer carries the
#      exact CM Insight marker ({"status":"UP","service":"cm-insight"}), not merely
#      any HTTP 2xx - a foreign service on the port is never mistaken for CM Insight
#
# Startup identity state (Goal 01B section B):
#   The PID is sampled for identity while the launch transition is still in progress:
#   the backgrounded launcher child carries the FORKED PARENT's command line until its
#   first execve, so a sample taken in that window legitimately reads "a different
#   process" for a PID that is ours. Actions run 36443449216 on 9c7168aa is the
#   observed consequence: PID 3451 was declared "reused by a different process" while
#   its log was still 0 bytes, and the same PID was our JVM a fraction of a second
#   later. Identity is therefore an explicit STATE, bounded in time:
#     provisional  the default: a contradictory reading is NOT proof of PID reuse
#     positive     an observation proved this PID is this application (its structural
#                  command-line identity, or process existence + the exact marker +
#                  ownership of the listening socket that serves the configured bind)
#     recycle      a refusal verdict: only ever reached from `positive`, or from
#                  `provisional` once the bounded launch grace (IDENT_GRACE, 3s, see
#                  the justification at its definition) has elapsed with no positive
#                  observation. The transition table is ci_start_identity_next.
#   Ownership safety is unchanged (section D of Goal 01A/01B): no PID is ever signalled
#   here, a contradictory identity is never adopted, and stop-time checks are untouched.
#   6. PUBLISHES run/cm-insight.pid atomically (temp file + rename) ONLY AFTER the
#      instance is confirmed: health OK, or the process verified in the
#      process-only modes (--timeout 0, web.port=0). A failed start never writes
#      and never deletes the PID file, so an instance that is already running can
#      never lose its tracking because of a later failed start
#   7. on failure names the actual condition and prints the decisive log line(s)
#      together with the tail of the log
#
# Bind awareness (Goal 01A section G):
#   one shared address model (bin/lib/cm-insight-addr.sh) decides where the health
#   probe dials and which sockets count as this configuration's listener:
#     127.0.0.1 and any other 127/8 literal -> probed at that literal itself
#     localhost                             -> probed at 127.0.0.1, then ::1
#     ::1                                   -> probed at ::1
#     wildcard (0.0.0.0, ::)                -> probed at a loopback address, and
#                                              a wildcard socket IS recognized as
#                                              this configuration's listener
#     a specific non-loopback address       -> probed at that address
#   A non-loopback bind is refused by the application itself unless the operator has
#   deliberately set web.allowInsecureHttp=true (Goal 01A section F); this script
#   reports that condition in its failure diagnosis and never weakens the policy.
#
# Tracking safety:
#   * the PID file is only ever created by a start that confirmed its instance;
#   * on any failure path only the private temp file is removed;
#   * a PID whose command line is not this application is never signalled.
#
# Untracked instance (recovery):
#   ./bin/status.sh              -> "Status: UNTRACKED INSTANCE" (exit 1)
#   ./bin/stop.sh --untracked    -> stops the process that owns the configured port
#                                   (only with positive CM Insight ownership evidence)
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 started, 1 startup failure or refusal to duplicate an instance,
# 2 usage error.
#
# Environment:
#   CM_INSIGHT_HOME        application home (default: the repository root). The default
#                          configuration file, the PID file and the log derive from it,
#                          using the same rule bin/cm-insight passes as -Dcminsight.home
#   CM_INSIGHT_CONFIG      configuration file (default <home>/conf/application.properties;
#                          a relative value resolves against the application home)
#   CM_INSIGHT_RUN_DIR     PID file directory (default <home>/run)
#   CM_INSIGHT_LOG_DIR     application log directory (default <home>/logs)

set -euo pipefail

TIMEOUT="${CM_INSIGHT_START_TIMEOUT:-30}"
APP_ARGS=()

# ---------------------------------------------------------------------------
# The bounded launch grace for the startup identity (Goal 01B section B)
# ---------------------------------------------------------------------------
# Why it exists: bin/start.sh backgrounds the launcher and samples the identity of
# $! immediately. Until that forked child has completed its FIRST execve it still
# carries this script's own command line (measured: a child blocked before its first
# execve reports the parent's argv), so ci_proc_identity correctly answers "1 =
# a different process" about a PID that is ours. That is exactly what run
# 36443449216 recorded: PID 3451 was declared recycled while its log was 0 bytes,
# and the same PID was our JVM a fraction of a second later.
#
# Why 3 seconds (derived from that evidence, not chosen as a round number):
#   * the transient ends when the child is first scheduled, and in the failing run the
#     ENTIRE launch-to-health sequence - which contains that window as its first
#     fraction - was over in "a fraction of a second" (0-byte log at the failing
#     sample, warnings plus a served health answer a moment later);
#   * the loop's own quantum is 1 second, so 3 seconds is at least 3x the whole
#     observed startup and two orders of magnitude above the sub-millisecond-per-exec
#     launcher chain the sample can catch, with room for a loaded shared runner;
#   * it matches the mirror-image rule already in this loop: GONE_STRIKES=3 refuses to
#     declare the process gone on one negative liveness sample either, so a negative
#     identity sample and a negative liveness sample are trusted with the same bounded
#     patience instead of two different magic numbers;
#   * it is a bound, not a sleep: the loop sleeps 1s per iteration anyway, so this only
#     defers a negative VERDICT. A genuinely recycled/foreign PID is still rejected -
#     as soon as the grace elapses - and a positive identity observation ends the
#     provisional window immediately.
IDENT_GRACE=3

usage() {
  cat <<'USAGE'
Usage: ./bin/start.sh [--timeout SECONDS] [--help] [-- application arguments...]

Starts CM Insight in the background and waits until the configured bind address
answers a health check with the exact CM Insight marker
({"status":"UP","service":"cm-insight"}).

Options:
  --timeout SECONDS   how long to wait for the health check (default 30, env
                      CM_INSIGHT_START_TIMEOUT). 0 means "check the process only".
  --help              show this help

With web.port=0 the application binds a random free port, so the health URL cannot
be derived from the configuration and only the process is verified.

Refuses to start (exit 1) when:
  * the configured bind address already answers /api/health as CM Insight but no
    PID file tracks it -> an UNTRACKED INSTANCE; inspect it with ./bin/status.sh
    and stop it with ./bin/stop.sh --untracked, or
  * another service already holds the configured port (whether it answers HTTP or
    not), or
  * the configured bind is non-loopback without the deliberate
    web.allowInsecureHttp=true override (the application refuses that itself).
A PID file that tracks a live CM Insight process is the "already running" fast
path (exit 0, nothing started).

Everything after "--" is passed to the application launcher (bin/cm-insight).

Environment:
  JAVA_HOME              JDK 17+ home (POSIX or Windows form)
  CM_INSIGHT_HOME        application home (default: the repository root); the default
                         configuration file, the PID file and the log live under it
  CM_INSIGHT_CONFIG      configuration file (default conf/application.properties under
                         the application home; a relative value resolves against it)
  CM_INSIGHT_START_TIMEOUT  default health-check timeout in seconds
  CM_INSIGHT_RUN_DIR     PID file directory (default <home>/run)
  CM_INSIGHT_LOG_DIR     application log directory (default <home>/logs)

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

LIB_DIR="${SCRIPT_DIR}/lib"
for module in cm-insight-addr.sh cm-insight-lifecycle.sh; do
  [ -r "${LIB_DIR}/${module}" ] || { printf 'ERROR: required module is missing: %s\n' "${LIB_DIR}/${module}" >&2; exit 2; }
done
# shellcheck source=/dev/null
. "${LIB_DIR}/cm-insight-lifecycle.sh"

# One path rule (Goal 01A section D), shared with bin/cm-insight: the application home
# is CM_INSIGHT_HOME when set, else the repository root, and the default config file,
# the PID file and the logs live under it. CM_INSIGHT_CONFIG / CM_INSIGHT_RUN_DIR /
# CM_INSIGHT_LOG_DIR stay explicit overrides; a relative CM_INSIGHT_CONFIG resolves
# against the application home.
APP_HOME="$(ci_effective_home "${ROOT}")"

RUN_DIR="${CM_INSIGHT_RUN_DIR:-${APP_HOME}/run}"
LOG_DIR="${CM_INSIGHT_LOG_DIR:-${APP_HOME}/logs}"
PID_FILE="${RUN_DIR}/cm-insight.pid"
OUT_FILE="${LOG_DIR}/cm-insight.out"
CONFIG="$(ci_effective_config "${APP_HOME}")"
PID_TMP=""

BIND="$(ci_effective_bind "${CONFIG}")"
PORT="$(ci_effective_port "${CONFIG}")"
BIND_DISPLAY="$(ci_bind_display "${BIND}" "${PORT}")"
PROBE_HOST="$(ci_addr_probe_host "${BIND}")"
HEALTH_URL="$(ci_health_url "${PROBE_HOST}" "${PORT}")"
BIND_LOOPBACK=false
ci_addr_is_loopback "${BIND}" && BIND_LOOPBACK=true

cleanup_tmp() {
  # Only ever removes the private candidate file: the published PID file is not
  # ours to delete on a failure path.
  if [ -n "${PID_TMP}" ]; then rm -f "${PID_TMP}" 2>/dev/null || true; fi
}
trap cleanup_tmp EXIT

log_ok() { printf 'OK: %s\n' "$*"; }
log_warn() { printf 'WARN: %s\n' "$*" >&2; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

listener_owner() {
  # prints "host|pid|exact|covered" for the first socket that serves the configured
  # bind (exact before covered) or nothing. Never a process-name guess: only the
  # port-scoped socket table is consulted.
  _ci_lo_port="$1"
  _ci_lo_bind="$2"
  for _ci_lo_class in exact covered; do
    ci_bind_listener_rows "${_ci_lo_port}" "${_ci_lo_bind}" | while IFS='|' read -r _ci_lo_host _ci_lo_pid _ci_lo_cls; do
      [ "${_ci_lo_cls}" = "${_ci_lo_class}" ] || continue
      printf '%s|%s|%s\n' "${_ci_lo_host}" "${_ci_lo_pid}" "${_ci_lo_cls}"
      break
    done
  done | sed -n '1p'
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
    printf 'the configured address %s is already bound by another process (bind failed)' "${BIND_DISPLAY}"
  elif printf '%s\n' "${_slice}" | grep -qi 'Configuration file not found'; then
    printf 'the configuration file could not be read'
  elif printf '%s\n' "${_slice}" | grep -qi 'allowInsecureHttp'; then
    printf 'plain HTTP on the non-loopback bind %s was refused; web.allowInsecureHttp=true is an explicitly accepted insecure exposure' "${BIND}"
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
  _lines="$(run_log_slice | grep -iE 'Address already in use|BindException|Configuration file not found|InvalidPathException|Unsupported database vendor|ConfigException|allowInsecureHttp|OutOfMemoryError|ERROR' 2>/dev/null | tail -n 3)"
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

print_bind_hint() {
  # Section F reminder for the one condition a bind-aware script must name: the
  # application itself owns the policy, this only explains a refusal.
  if [ "${BIND_LOOPBACK}" != true ]; then
    printf '       note: web.bind=%s is not loopback, so plain HTTP is refused unless the operator deliberately sets web.allowInsecureHttp=true (a SECURITY WARNING at startup).\n' "${BIND}" >&2
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
      if ci_pid_is_running "${OLD_PID}" "${PORT}" "${BIND}"; then
        set +e
        ci_proc_identity "${OLD_PID}"
        IDENT_RC=$?
        set -e
        case "${IDENT_RC}" in
          0) log_ok "CM Insight is already running (PID ${OLD_PID}); nothing to do"; exit 0 ;;
          1) log_warn "PID file ${PID_FILE} points at PID ${OLD_PID}, which is a different process; removing the stale PID file and continuing" ;;
          *) log_warn "PID file ${PID_FILE} points at live PID ${OLD_PID} but its command line cannot be inspected; removing the stale PID file and continuing (the port check below still refuses a second instance)" ;;
        esac
        rm -f "${PID_FILE}"
      else
        log_warn "removing stale PID file ${PID_FILE} (PID ${OLD_PID} is not running: absent from the process table and from the socket table)"
        rm -f "${PID_FILE}"
      fi
      ;;
  esac
fi

# ---------------------------------------------------------------------------
# 2. untracked instance / occupied-port pre-flight (bind-aware)
# ---------------------------------------------------------------------------
ci_health_probe "${PORT}" "${BIND}"
LISTENER="$(listener_owner "${PORT}" "${BIND}" || true)"
LISTEN_HOST="${LISTENER%%|*}"
LISTEN_REST="${LISTENER#*|}"
LISTEN_PID="${LISTEN_REST%%|*}"
LISTEN_CLASS="${LISTEN_REST##*|}"
[ -n "${LISTENER}" ] || { LISTEN_HOST=""; LISTEN_PID=""; LISTEN_CLASS=""; }

listener_detail() {
  ci_describe_socket "${LISTEN_HOST}" "${LISTEN_CLASS}" "${BIND}" "${PORT}"
}

case "${CI_HEALTH_KIND}" in
  our)
    printf 'ERROR: an instance is already serving %s but is not tracked by %s; refusing to start a second instance.\n' "${CI_HEALTH_URL}" "${PID_FILE}" >&2
    printf '       Inspect it with ./bin/status.sh (Status: UNTRACKED INSTANCE) and stop it with ./bin/stop.sh --untracked. No PID file was written or removed.\n' >&2
    exit 1
    ;;
  foreign)
    printf 'ERROR: %s answered HTTP %s without the cm-insight health marker; refusing to start (another service holds the configured port, or an instance reports an unexpected health body).\n' "${CI_HEALTH_URL}" "${CI_HEALTH_CODE}" >&2
    printf '       The marker must be the exact {"status":"UP","service":"cm-insight"} body. Stop that service or choose another web.port in %s.\n' "${CONFIG}" >&2
    exit 1
    ;;
  *)
    if [ -n "${LISTEN_PID}" ] || [ -n "${LISTEN_HOST}" ]; then
      printf 'ERROR: %s is already bound (%s) but /api/health does not answer as CM Insight; refusing to start.\n' "${BIND_DISPLAY}" "$(listener_detail)" >&2
      case "${LISTEN_PID}" in
        ''|-)
          printf '       The owner could not be resolved on this platform; the port belongs to somebody else.\n' >&2
          ;;
        *)
          printf '       Owner PID %s: another service, a hung instance, or an instance that was not published in %s (it is still untracked).\n' "${LISTEN_PID}" "${PID_FILE}" >&2
          ;;
      esac
      printf '       Inspect it with ./bin/status.sh, stop an untracked instance with ./bin/stop.sh --untracked, or choose another web.port.\n' >&2
      exit 1
    fi
    ;;
esac
if [ "${CI_HEALTH_KIND}" = "unknown" ]; then
  if [ "${PORT}" = "0" ]; then
    log_warn "web.port=0 binds a random free port; the untracked-instance and port-conflict checks cannot run"
  else
    log_warn "the port could not be probed (curl unavailable); the untracked-instance and port-conflict checks were skipped"
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

WAITED=0
HEALTH_OK=false
PROCESS_ONLY=false
PROC_GONE=false
RECYCLED=false
GONE_STRIKES=0
HEALTH_CONFIRMED_URL=""

# Startup identity state (Goal 01B section B). See IDENT_GRACE above for the bound and
# ci_start_identity_next (bin/lib/cm-insight-lifecycle.sh) for the transition table.
IDENT_STATE="provisional"     # provisional | positive
IDENT_SAW_DIFFERENT=false     # a contradictory reading was witnessed while provisional
IDENT_DIFFERENT_SAMPLES=0
IDENT_LAST_CMD=""
MARKER_UNTIED=false           # the marker answers, but not on a socket this PID owns
IDENT_UNPROVEN=false          # process-only mode: contradictory argv, never a positive one

while : ; do
  # "Gone" is only ever concluded from BOTH nets (review finding H-3). The MSYS pid of a
  # process that exec'd native java.exe can vanish while the Windows process keeps
  # serving; trusting kill -0 alone made a healthy start report "exited during startup".
  #
  # A single negative observation is ALSO not proof on a loaded runner: between the fork
  # that returns $! and the exec that makes the process inspectable, and before the
  # application has bound its socket, BOTH nets can be transiently negative. Concluding
  # "gone" on the first such observation is a race, so it now takes GONE_STRIKES
  # consecutive negative observations (a genuinely dead process stays dead, so this
  # delays the verdict without ever hiding a real failure).
  ALIVE_NOW=true
  if ! ci_process_exists "${PID}"; then
    if ci_pid_owns_serving_socket "${PID}" "${PORT}" "${BIND}"; then
      ALIVE_NOW=true
    else
      ALIVE_NOW=false
    fi
  fi
  if [ "${ALIVE_NOW}" != true ]; then
    GONE_STRIKES=$((GONE_STRIKES + 1))
    if [ "${GONE_STRIKES}" -ge 3 ]; then PROC_GONE=true; break; fi
    sleep 1
    WAITED=$((WAITED + 1))
    continue
  fi
  GONE_STRIKES=0

  # -------------------------------------------------------------------------
  # Startup identity sample (Goal 01B section B)
  #
  # The state machine decides whether this sample may be read as PID reuse. While the
  # identity is provisional a contradictory reading is NOT proof (the backgrounded
  # child has not exec'd yet), and the bounded grace makes sure that patience cannot be
  # exploited: after IDENT_GRACE seconds without a positive observation the very next
  # contradictory sample is recycle. "Cannot tell" (rc=2) never moves the state.
  # -------------------------------------------------------------------------
  set +e
  ci_proc_identity "${PID}"
  IDENT_RC=$?
  set -e
  IDENT_GRACE_ELAPSED=false
  [ "${WAITED}" -lt "${IDENT_GRACE}" ] || IDENT_GRACE_ELAPSED=true
  IDENT_NEXT="$(ci_start_identity_next "${IDENT_STATE}" "${IDENT_RC}" "${IDENT_GRACE_ELAPSED}")"
  if [ "${IDENT_NEXT}" = "recycle" ]; then RECYCLED=true; break; fi
  if [ "${IDENT_RC}" -eq 1 ]; then
    # Witnessed but provisional: recorded as evidence for the diagnosis below and never
    # acted on as a verdict. This is the line that makes a CI failure self-describing.
    IDENT_SAW_DIFFERENT=true
    IDENT_DIFFERENT_SAMPLES=$((IDENT_DIFFERENT_SAMPLES + 1))
    IDENT_LAST_CMD="$(ci_proc_cmdline "${PID}" 2>/dev/null || true)"
    log_warn "PID ${PID} reads as a different process ${WAITED}s into the launch; the identity is provisional for ${IDENT_GRACE}s (sample ${IDENT_DIFFERENT_SAMPLES}), so this is not yet proof of PID reuse. Observed command line: $(printf '%.200s' "${IDENT_LAST_CMD:-<not inspectable>}")"
  fi
  IDENT_STATE="${IDENT_NEXT}"

  if [ "${USE_CURL}" = true ]; then
    # The exact marker is required, not any 2xx answer, and every probe candidate of
    # the configured bind is tried (127.0.0.1 and/or ::1 for a wildcard bind).
    ci_health_probe "${PORT}" "${BIND}"
    if [ "${CI_HEALTH_KIND}" = "our" ]; then
      if [ "${IDENT_STATE}" = "positive" ]; then
        HEALTH_OK=true
        HEALTH_CONFIRMED_URL="${CI_HEALTH_URL}"
        break
      fi
      if [ "${IDENT_SAW_DIFFERENT}" != true ]; then
        # No contradictory reading was ever witnessed (including the platforms where a
        # command line cannot be inspected at all): the exact marker keeps the weight
        # it already had in Goal 01A.
        HEALTH_OK=true
        HEALTH_CONFIRMED_URL="${CI_HEALTH_URL}"
        break
      fi
      if ci_pid_owns_serving_socket "${PID}" "${PORT}" "${BIND}"; then
        # POSITIVE evidence from the combination Goal 01B section B asks for: the PID
        # exists, the EXACT marker answers, and that PID owns the listening socket which
        # serves the configured bind. From here on a contradictory reading is recycle.
        IDENT_STATE="positive"
        HEALTH_OK=true
        HEALTH_CONFIRMED_URL="${CI_HEALTH_URL}"
        break
      fi
      if [ -z "$(ci_listener_pids "${PORT}" "${BIND}" 2>/dev/null || true)" ]; then
        # No serving socket on this port carries an attributable owner on this platform,
        # so ownership cannot be witnessed here. The pre-existing marker rule stands
        # rather than refusing an instance this platform cannot attribute.
        HEALTH_OK=true
        HEALTH_CONFIRMED_URL="${CI_HEALTH_URL}"
        break
      fi
      # The marker answers, but this PID cannot be tied to the socket that served it: do
      # NOT publish the PID file. Keep waiting (bounded by TIMEOUT) - the real instance
      # may still be coming up, and a foreign/untracked one must never be adopted.
      MARKER_UNTIED=true
    fi
  elif [ "${WAITED}" -ge 3 ]; then
    # Process-only mode (web.port=0 binds an unknown port, curl may be absent, or --timeout 0
    # asked for the process only): there is no health marker and no socket evidence to fall
    # back on, so the SAME evidence bar as in the marker branch applies - the PID file is
    # published only when a positive identity was observed OR no contradictory reading ever
    # was. A witnessed contradiction with no positive observation keeps waiting (bounded by
    # TIMEOUT) instead of publishing a PID file for a pid whose identity could not be shown.
    if [ "${IDENT_STATE}" = "positive" ] || [ "${IDENT_SAW_DIFFERENT}" != true ]; then
      PROCESS_ONLY=true
      break
    fi
    IDENT_UNPROVEN=true
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
  log_ok "CM Insight started (PID ${PID}); health OK on ${HEALTH_CONFIRMED_URL:-${HEALTH_URL}} (web.bind=${BIND})"
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
  printf 'ERROR: CM Insight exited during startup (PID %s): %s.\n' "${PID}" "${REASON}" >&2
  printf '       Nothing is running and no PID file was written or removed; check the port and the configuration.\n' >&2
  print_bind_hint
  print_decisive_lines "${OUT_FILE}"
  print_log_tail
  exit 1
fi

if [ "${RECYCLED}" = true ]; then
  # Reached ONLY through ci_start_identity_next's "recycle" verdict: a contradictory
  # reading after a positive identity had been established, or a contradictory reading
  # that outlived the whole bounded launch grace (IDENT_GRACE). One transient sample in
  # the launch transition is never enough (Goal 01B section B). The identity evidence is
  # printed so a CI failure is self-describing instead of a bare "exit code 1".
  fail_start "PID ${PID} was reused by a different process before the health check succeeded (identity state: ${IDENT_STATE}; ${IDENT_DIFFERENT_SAMPLES} contradictory sample(s) inside the ${IDENT_GRACE}s launch grace; observed command line: $(printf '%.200s' "${IDENT_LAST_CMD:-<not inspectable>}")): ${REASON}." \
             "The application is not confirmably running; no PID file was written or removed."
fi

if [ "${MARKER_UNTIED}" = true ]; then
  printf 'ERROR: the exact CM Insight marker answered on %s, but the tracked PID %s could not be tied to it: its command line reads as a different process (observed: %s) and it does not own the listening socket that serves web.bind=%s.\n' \
    "${HEALTH_URL}" "${PID}" "$(printf '%.200s' "${IDENT_LAST_CMD:-<not inspectable>}")" "${BIND}" >&2
  printf '       The marker may be another instance or a process that appeared after launch, so NO PID file was written; inspect it with ./bin/status.sh (Status: UNTRACKED INSTANCE).\n' >&2
  print_bind_hint
  print_decisive_lines "${OUT_FILE}"
  print_log_tail
  exit 1
fi

if [ "${IDENT_UNPROVEN}" = true ]; then
  # Process-only mode, no positive identity, at least one contradictory reading: publishing
  # the PID file would track a pid whose identity was never shown, which the pre-fix code
  # refused as well (it refused on the first contradictory sample).
  printf 'ERROR: CM Insight (PID %s) is alive, but its command line read as a different process during the launch and no positive CM Insight identity was ever observed.\n' "${PID}" >&2
  printf '       Last observed command line: %s\n' "$(printf '%.200s' "${IDENT_LAST_CMD:-<not inspectable>}")" >&2
  printf '       This start used a process-only check (%s), so there is no health marker to fall back on; NO PID file was written.\n' "${SKIP_REASON:-process-only}" >&2
  print_decisive_lines "${OUT_FILE}"
  print_log_tail
  exit 1
fi

printf 'ERROR: CM Insight (PID %s) did not answer %s within %ss: %s.\n' "${PID}" "${HEALTH_URL}" "${TIMEOUT}" "${REASON}" >&2
printf '       The process is still alive but unconfirmed, so NO PID file was written; stop it with ./bin/stop.sh --untracked, then investigate.\n' >&2
print_bind_hint
print_decisive_lines "${OUT_FILE}"
print_log_tail
exit 1
