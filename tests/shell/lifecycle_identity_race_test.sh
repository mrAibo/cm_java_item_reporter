#!/usr/bin/env bash
#
# CM Insight - committed lifecycle regression: the Linux startup identity race
# (Goal 01B sections B, C, D, F and H). Part of the tests/shell suite the CI runner executes.
#
#   bash tests/shell/lifecycle_identity_race_test.sh      (no arguments, no required env)
#
# Exit codes: 0 pass, 1 fail. On a non-Linux development host the file prints an explicit
# skip and exits 0 (see PLATFORM GATE below) - in CI (CI=true) that gate is a FAILURE, so
# the suite can never silently stop covering the Linux lifecycle.
#
# ---------------------------------------------------------------------------
# WHAT THIS PINS, AND WHY IT IS A REAL GATE
# ---------------------------------------------------------------------------
#
# PHASE 1 - bounded soak, 10 real start/status/stop cycles (the reliability gate)
#   Every cycle runs the real scripts on an isolated ephemeral port against a throwaway
#   CM_INSIGHT_HOME/RUN/LOG, and asserts: start.sh confirmed health, status.sh reports
#   RUNNING + Health: OK, the PID file tracks a LIVE CM INSIGHT IDENTITY, stop.sh stops it
#   through the tracked (ownership-checked) path, the PID file is gone and the port answers
#   nothing. Cleanup runs in every iteration and again in the EXIT trap, so a failure can
#   never leave a JVM or a PID file behind.
#
# PHASE 2 - the launch transition, made DETERMINISTIC (the section B defect gate)
#   The defect (GitHub Actions run 36443449216 on SHA 9c7168aa): start.sh declared
#   `PID 3451 was reused by a different process` while that PID's log was still 0 bytes,
#   and the very same PID was our JVM a fraction of a second later. The observation that
#   fired it is real and reproducible in principle: bin/start.sh backgrounds the launcher
#   and samples $! immediately, and until that forked child has completed its FIRST execve
#   it still carries the PARENT's command line. Measured on Linux: a child blocked before
#   its first exec is reported by /proc/<pid>/cmdline as the parent's argv, and
#   ci_proc_identity correctly answers "1 = a different process" for it.
#
#   A fork/exec window of a few milliseconds is not something a test can time reliably, so
#   this file WIDENS it with no production hook at all: the child's stdout/stderr target
#   (logs/cm-insight.out) is a SYMLINK TO A FIFO. The forked child blocks in open(2) on
#   that FIFO - state "S", wchan "wait_for_partner" - so it cannot have exec'd while the
#   test holds the reader closed; opening the reader releases it into the normal launcher
#   chain. That is the same code path start.sh uses (`nohup ... >> "${OUT_FILE}" 2>&1 &`),
#   only the timing is under test control.
#
#   ASSERTION THAT FAILS AGAINST THE PRE-FIX bin/start.sh: with the child deterministically
#   still in its pre-exec image, the OLD code read ci_proc_identity == 1 on its first sample
#   and aborted with exit 1 and
#   `ERROR: PID <n> was reused by a different process before the health check succeeded`.
#   This phase requires that same start to succeed (rc=0) AND requires the transient to have
#   been WITNESSED (`reads as a different process` + `identity is provisional` in the
#   output), so the fixture itself is checked: if the launch transition ever stopped
#   reproducing, the phase fails loudly instead of passing vacuously. It is also a two-sided
#   gate: the grace may not become "adopt whatever the pid is now" - PHASE 3 pins that a
#   foreign PID is still refused, and the transition table is pinned at function level by
#   the suite.
#
# PHASE 3 - an unrelated live process must never be signalled
#   A PID file holding a live non-CM-Insight process: stop.sh must report a different
#   process, remove the stale PID file, exit 0 - and that process must still be alive
#   afterwards. The startup grace may not weaken the stop-time ownership rule.
#
# PHASE 4 - the exact marker must be TIED to the socket that served it (sections C/D)
#   On Linux the socket table spells an IPv4 loopback listener `::ffff:127.0.0.1`, which is
#   the form the failing CI run showed. stop.sh ties the health answer to the socket it is
#   about to signal; with raw string equality the marker at 127.0.0.1 and the row
#   ::ffff:127.0.0.1 never matched, so the marker could not be tied to the socket owner at
#   all (section D's requirement). This phase runs a marker listener whose argv does NOT
#   identify CM Insight - so the marker-to-socket tie is the ONLY admissible evidence - bound
#   to the IPv4-mapped IPv6 address, and requires `stop.sh --untracked` to stop it. Pre-fix
#   that run ends in "REFUSING to signal PID ..." (or, before section C, in "the socket ...
#   could not be attributed"): both are FAILs.
#
# ---------------------------------------------------------------------------
# SAFETY / ISOLATION
# ---------------------------------------------------------------------------
#   * Nothing in the repository is written: conf/, run/, logs/, data/, reports/ are bypassed
#     through CM_INSIGHT_HOME, CM_INSIGHT_CONFIG, CM_INSIGHT_RUN_DIR and CM_INSIGHT_LOG_DIR,
#     all pointing into a private mktemp directory.
#   * The port is chosen from a free, ephemeral-range port; the configuration is the
#     repository's own conf/application.properties.example with web.bind/web.port replaced.
#   * Every signal this file sends is either to a process it started itself for a fixture
#     (an unrelated `sleep`, the marker listener) or issued by bin/stop.sh.
#   * offline: nothing here uses the network beyond 127.0.0.1.
#
# Environment knobs (all optional): CM_INSIGHT_TEST_CYCLES (default 10),
#   CM_INSIGHT_TEST_PORT (default: a free port is searched), CM_INSIGHT_TEST_STRICT=1.

set -u -o pipefail

SELF="${BASH_SOURCE[0]:-$0}"
TEST_DIR="$(cd -- "$(dirname -- "${SELF}")" && pwd)"
ROOT="$(cd -- "${TEST_DIR}/../.." && pwd)"
SELF_REL="${SELF#"${ROOT}"/}"

CYCLES="${CM_INSIGHT_TEST_CYCLES:-10}"
case "${CYCLES}" in ''|*[!0-9]*) CYCLES=10 ;; esac
PORT="${CM_INSIGHT_TEST_PORT:-}"
STRICT="${CM_INSIGHT_TEST_STRICT:-${CI:-false}}"

PASSED=0
FAILED=0
START_TS="$(date +%s)"
WORK=""
HOMES=""
EXTRA_PIDS=""

elapsed() { printf '%s' "$(( $(date +%s) - START_TS ))"; }
ok()   { PASSED=$((PASSED + 1)); printf 'ok:   %s\n' "$*"; }
bad()  { FAILED=$((FAILED + 1)); printf 'FAIL: %s\n' "$*"; }
note() { printf -- '-- %s\n' "$*"; }
raw()  { sed 's/^/      | /' "$1"; }

summary() {
  # Prints the runner contract's final line and returns 0 only for a clean run.
  printf -- '--\n'
  if [ "${FAILED}" -gt 0 ]; then
    printf 'FAIL: %s (%d failed of %d assertions, elapsed %ss)\n' \
      "${SELF_REL}" "${FAILED}" "$((PASSED + FAILED))" "$(elapsed)"
    return 1
  fi
  printf 'PASS: %s (%d assertions, %s cycles, port %s, elapsed %ss)\n' \
    "${SELF_REL}" "${PASSED}" "${CYCLES}" "${PORT:-none}" "$(elapsed)"
  return 0
}

cleanup() {
  # Never leave an instance, a PID file or a temp tree behind. Best-effort by design: the
  # verdict is already decided and a cleanup failure must not rewrite it, so it runs with
  # `set +e` and always returns 0.
  set +e +u
  for _p in ${EXTRA_PIDS:-}; do kill "${_p}" 2>/dev/null; done
  for _p in ${HOMES:-}; do
    if [ -f "${_p}/run/cm-insight.pid" ]; then
      ( CM_INSIGHT_HOME="${_p}" \
        CM_INSIGHT_CONFIG="${_p}/conf/application.properties" \
        CM_INSIGHT_RUN_DIR="${_p}/run" \
        CM_INSIGHT_LOG_DIR="${_p}/logs" \
        timeout 25 "${ROOT}/bin/stop.sh" --force --timeout 10 >/dev/null 2>&1 )
    fi
  done
  reap_isolated_port
  if [ -n "${WORK}" ] && [ -d "${WORK}" ]; then rm -rf -- "${WORK}"; fi
  return 0
}

# ---- platform gate --------------------------------------------------------
PLATFORM="$(uname -s 2>/dev/null || printf unknown)"
if [ "${PLATFORM}" != "Linux" ]; then
  if [ "${STRICT}" = "true" ]; then
    bad "this file pins LINUX process semantics (fork/exec argv via /proc, FIFO-blocked launch transition, ss listener ownership) and must run on Linux; uname -s reports '${PLATFORM}'. Run it on ubuntu-latest (the CI runner does)."
    summary
    exit 1
  fi
  printf 'skip: platform %s is not Linux: the launch-transition and listener-ownership cases are Linux-only\n' "${PLATFORM}"
  printf 'skip: this host cannot exercise /proc argv semantics, so the file is skipped instead of reporting a false pass\n'
  summary
  exit 0
fi

# ---- prerequisites --------------------------------------------------------
prereq_failed=false
require_cmd() {
  if command -v "$1" >/dev/null 2>&1; then
    ok "prerequisite: $1 ($(command -v "$1"))"
  else
    bad "prerequisite missing: $1 is required ${2:-for this test}"
    prereq_failed=true
  fi
}

require_cmd bash "to run this file"
require_cmd curl "for the exact-health-marker probe"
require_cmd mkfifo "for the launch-transition fixture"
require_cmd timeout "to bound the fixture if a step ever blocks"
if command -v ss >/dev/null 2>&1; then
  ok "prerequisite: ss ($(command -v ss)) - listener ownership evidence"
elif command -v netstat >/dev/null 2>&1; then
  ok "prerequisite: netstat ($(command -v netstat)) - listener ownership evidence"
else
  bad "prerequisite missing: neither ss nor netstat is available, so listener ownership cannot be witnessed"
  prereq_failed=true
fi

JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi
if [ -n "${JAVA_BIN}" ]; then
  JAVA_LINE="$("${JAVA_BIN}" -version 2>&1 | head -n 1)"
  JAVA_MAJOR="${JAVA_LINE#*version }"
  JAVA_MAJOR="${JAVA_MAJOR%%.*}"
  JAVA_MAJOR="$(printf '%s' "${JAVA_MAJOR}" | tr -cd '0-9')"
  if [ -n "${JAVA_MAJOR}" ] && [ "${JAVA_MAJOR}" -ge 17 ]; then
    ok "prerequisite: java ${JAVA_LINE} (JDK 17+ required by the build)"
  else
    bad "prerequisite: JDK 17+ required, found '${JAVA_LINE:-none}' at ${JAVA_BIN:-none}; set JAVA_HOME to a JDK 17+"
    prereq_failed=true
  fi
else
  bad "prerequisite missing: java (JDK 17+); set JAVA_HOME or put java on PATH"
  prereq_failed=true
fi

JAR="${ROOT}/build/cm-insight.jar"
if [ -s "${JAR}" ]; then
  ok "prerequisite: ${JAR} ($(wc -c < "${JAR}") bytes)"
else
  bad "prerequisite missing: ${JAR} is absent or empty; run ./build.sh before this test"
  prereq_failed=true
fi

CONFIG_EXAMPLE="${ROOT}/conf/application.properties.example"
if [ -s "${CONFIG_EXAMPLE}" ]; then
  ok "prerequisite: ${CONFIG_EXAMPLE}"
else
  bad "prerequisite missing: ${CONFIG_EXAMPLE} (the test derives its isolated configuration from it)"
  prereq_failed=true
fi

if [ "${prereq_failed}" = true ]; then
  summary
  exit 1
fi

# ---- shared helpers -------------------------------------------------------
. "${ROOT}/bin/lib/cm-insight-lifecycle.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cm-insight-lifecycle-test.XXXXXX")"
trap 'rc=$?; trap - EXIT; cleanup; exit "${rc}"' EXIT

pid_identity() {
  # $1 = pid; prints the ci_proc_identity result code (0 = CM Insight) WITHOUT touching the
  # shell's `set -e` state (this file runs without `set -e` on purpose: every assertion is
  # explicit and a failing probe must never abort the reporting).
  if ci_proc_identity "$1"; then printf '0'; else printf '%s' "$?"; fi
}

# Isolated port: an explicitly requested one, else the first free port in a random slice of
# the ephemeral range. "Free" = absent from the listener table AND refusing a TCP connect.
port_busy() {
  _p="$1"
  if command -v ss >/dev/null 2>&1; then
    if ss -ltn 2>/dev/null | awk -v s=":${_p}" 'NR > 1 { if (substr($4, length($4) - length(s) + 1) == s) { found = 1 } } END { exit(found ? 0 : 1) }'; then
      return 0
    fi
  fi
  if (exec 3<>"/dev/tcp/127.0.0.1/${_p}") 2>/dev/null; then
    return 0
  fi
  return 1
}

if [ -n "${PORT}" ]; then
  if port_busy "${PORT}"; then bad "CM_INSIGHT_TEST_PORT=${PORT} is already in use; choose a free port"; summary; exit 1; fi
else
  _cand=$(( 20000 + (RANDOM % 20000) ))
  _tries=0
  while [ "${_tries}" -lt 60 ]; do
    if ! port_busy "${_cand}"; then PORT="${_cand}"; break; fi
    _cand=$(( _cand + 1 ))
    _tries=$(( _tries + 1 ))
  done
  if [ -z "${PORT}" ]; then bad "no free port found near ${_cand}; set CM_INSIGHT_TEST_PORT"; summary; exit 1; fi
fi
ok "isolated port ${PORT} is free (checked against the listener table and a TCP connect)"

reap_isolated_port() {
  # Last-resort leak guard for THIS test's isolated port only. An instance that was started
  # but never published a PID file (exactly what a failing start looks like) cannot be reached
  # through the PID file, so it is reaped through the same ownership evidence stop.sh uses -
  # and only when one of them holds: structural CM Insight argv, or the exact CM Insight
  # marker answering on the isolated port.
  [ -n "${PORT}" ] || return 0
  for _rp in $(ci_listener_pids "${PORT}" "127.0.0.1" 2>/dev/null); do
    case "${_rp}" in ''|*[!0-9]*) continue ;; esac
    if [ "$(pid_identity "${_rp}")" = "0" ] || ci_health_marker_ok "$(ci_health_url 127.0.0.1 "${PORT}")"; then
      kill -TERM "${_rp}" 2>/dev/null
    fi
  done
  return 0
}

make_home() {
  # $1 = home directory; creates an isolated application home whose configuration is the
  # repository's own example with only web.bind/web.port replaced.
  _h="$1"
  mkdir -p "${_h}/conf/profiles" "${_h}/conf/secrets" "${_h}/run" "${_h}/logs" "${_h}/data" "${_h}/reports" || return 1
  sed -e 's|^web\.bind=.*|web.bind=127.0.0.1|' -e "s|^web\.port=.*|web.port=${PORT}|" \
      "${CONFIG_EXAMPLE}" > "${_h}/conf/application.properties" || return 1
  grep -q "^web.port=${PORT}\$" "${_h}/conf/application.properties" || return 1
  grep -q '^web.bind=127\.0\.0\.1$' "${_h}/conf/application.properties" || return 1
  HOMES="${HOMES} ${_h}"
  return 0
}

cm_run() {
  # $1 = home, $2 = script name under bin/, rest = script arguments. Runs a repository
  # lifecycle script against that home with every repo-visible path redirected into it.
  _home="$1"
  _script="$2"
  shift 2
  CM_INSIGHT_HOME="${_home}" \
  CM_INSIGHT_CONFIG="${_home}/conf/application.properties" \
  CM_INSIGHT_RUN_DIR="${_home}/run" \
  CM_INSIGHT_LOG_DIR="${_home}/logs" \
  "${ROOT}/bin/${_script}" "$@"
}

probe_port() {
  # $1 = home; prints CI_HEALTH_KIND (our|foreign|none|unknown) for the configured bind.
  _bind="$(ci_effective_bind "$1/conf/application.properties")"
  ci_health_probe "${PORT}" "${_bind}"
  printf '%s' "${CI_HEALTH_KIND}"
}

all_stopped_and_silent() {
  # $1 = home, $2 = label; asserts PID file gone + nothing answering on the port.
  _h="$1"
  _label="$2"
  if [ -f "${_h}/run/cm-insight.pid" ]; then
    bad "${_label}: PID file ${_h}/run/cm-insight.pid still exists after stop.sh"
    return 1
  fi
  ok "${_label}: PID file removed by stop.sh"
  _kind="$(probe_port "${_h}")"
  if [ "${_kind}" = "our" ]; then
    bad "${_label}: port ${PORT} still answers the CM Insight marker after stop"
    return 1
  fi
  ok "${_label}: port ${PORT} answers nothing/has no CM Insight listener after stop (probe: ${_kind})"
  return 0
}

one_cycle() {
  # $1 = home, $2 = cycle number. Runs start -> status -> stop, asserting every step in
  # order (so a failure is reported at the step that failed, with that step's raw output).
  _h="$1"
  _n="$2"
  _label="cycle ${_n}/${CYCLES}"
  _dir="${WORK}/cycle-${_n}"
  mkdir -p "${_dir}" 2>/dev/null

  cm_run "${_h}" start.sh --timeout 45 > "${_dir}/start.log" 2>&1
  _s_rc=$?
  if [ "${_s_rc}" -ne 0 ]; then
    bad "${_label}: start.sh exited ${_s_rc} (expected 0)"
    raw "${_dir}/start.log"
    cm_run "${_h}" stop.sh --force --timeout 10 >/dev/null 2>&1
    return 1
  fi
  if grep -q 'CM Insight started' "${_dir}/start.log"; then
    ok "${_label}: start.sh rc=0 and reported a confirmed start on the configured bind"
  else
    bad "${_label}: start.sh exited 0 but did not report a confirmed start"
    raw "${_dir}/start.log"
  fi

  _pid="$(head -n 1 "${_h}/run/cm-insight.pid" 2>/dev/null)"
  case "${_pid}" in
    ''|*[!0-9]*) bad "${_label}: the published PID file does not hold a numeric PID ('${_pid}')" ;;
    *)
      _ident="$(pid_identity "${_pid}")"
      if [ "${_ident}" = "0" ]; then
        ok "${_label}: the PID file tracks PID ${_pid}, whose structural command line IS CM Insight (ci_proc_identity=0)"
      else
        bad "${_label}: the PID file tracks PID ${_pid}, but ci_proc_identity=${_ident} (0 = CM Insight)"
      fi
      ;;
  esac

  cm_run "${_h}" status.sh > "${_dir}/status.log" 2>&1
  _t_rc=$?
  if [ "${_t_rc}" -ne 0 ]; then
    bad "${_label}: status.sh exited ${_t_rc} (expected 0)"
  elif grep -q '^Status: RUNNING' "${_dir}/status.log" && grep -q '^Health: OK' "${_dir}/status.log"; then
    ok "${_label}: status.sh reports Status: RUNNING and Health: OK"
  else
    bad "${_label}: status.sh did not report RUNNING + Health: OK"
  fi

  cm_run "${_h}" stop.sh --timeout 20 > "${_dir}/stop.log" 2>&1
  _p_rc=$?
  if [ "${_p_rc}" -ne 0 ]; then
    bad "${_label}: stop.sh exited ${_p_rc} (expected 0)"
    raw "${_dir}/start.log"
  else
    ok "${_label}: stop.sh rc=0 (tracked, ownership-checked stop)"
  fi
  raw "${_dir}/status.log"
  raw "${_dir}/stop.log"
  all_stopped_and_silent "${_h}" "${_label}"
  return 0
}

# ===========================================================================
# PHASE 1 - bounded soak: real start/status/stop cycles
# ===========================================================================
note "PHASE 1/4: ${CYCLES} real start/status/stop cycles on port ${PORT} (isolated home)"
SOAK_HOME="${WORK}/soak/home"
if ! make_home "${SOAK_HOME}"; then
  bad "PHASE 1: could not build the isolated home/configuration under ${SOAK_HOME}"
else
  ok "PHASE 1: isolated home ${SOAK_HOME} (config derived from conf/application.properties.example, web.bind=127.0.0.1, web.port=${PORT})"
  _i=1
  while [ "${_i}" -le "${CYCLES}" ]; do
    one_cycle "${SOAK_HOME}" "${_i}"
    _i=$(( _i + 1 ))
  done
fi

# ===========================================================================
# PHASE 2 - the launch transition (deterministic; fails against the pre-fix code)
# ===========================================================================
note "PHASE 2/4: launch transition with the forked child blocked before its first execve"
T="${WORK}/transition"
if ! make_home "${T}/home"; then
  bad "PHASE 2: could not build the isolated home/configuration under ${T}/home"
else
  ok "PHASE 2: isolated home ${T}/home"
  mkfifo "${T}/log.fifo"
  # The child blocks in open(2) here until the reader below is opened: pre-exec argv.
  ln -s "${T}/log.fifo" "${T}/home/logs/cm-insight.out"
  ok "PHASE 2: fixture ready - logs/cm-insight.out is a symlink to a FIFO, so the backgrounded child cannot exec until the test releases it"

  (
    export CM_INSIGHT_HOME="${T}/home"
    export CM_INSIGHT_CONFIG="${T}/home/conf/application.properties"
    export CM_INSIGHT_RUN_DIR="${T}/home/run"
    export CM_INSIGHT_LOG_DIR="${T}/home/logs"
    cd "${ROOT}" || exit 1
    timeout 60 "${ROOT}/bin/start.sh" --timeout 45 >> "${T}/start.log" 2>&1
  ) &
  START_WRAPPER=$!

  # Wait for the transient to be WITNESSED (post-fix) or for start.sh to finish (pre-fix:
  # it aborts with the "reused by a different process" error on its very first sample).
  _waited=0
  while [ "${_waited}" -lt 100 ]; do
    if grep -q 'identity is provisional' "${T}/start.log" 2>/dev/null; then break; fi
    if ! kill -0 "${START_WRAPPER}" 2>/dev/null; then break; fi
    sleep 0.1
    _waited=$(( _waited + 1 ))
  done

  # Release the blocked child: the reader's open(2) completes the child's redirection, so
  # the real launcher chain runs and the JVM starts.
  timeout 60 cat "${T}/log.fifo" > "${T}/drained.log" &
  READER=$!
  EXTRA_PIDS="${EXTRA_PIDS} ${READER}"

  wait "${START_WRAPPER}"
  S_RC=$?

  if grep -q 'identity is provisional' "${T}/start.log" 2>/dev/null; then
    ok "PHASE 2: the launch transition WAS witnessed: start.sh saw a contradictory identity reading, called it provisional and kept waiting (the pre-fix code never prints this line)"
  else
    bad "PHASE 2: fixture check failed - the launch transition was never witnessed (no 'identity is provisional' line)"
  fi
  raw "${T}/start.log"

  if [ "${S_RC}" -ne 0 ]; then
    bad "PHASE 2: start.sh exited ${S_RC} (expected 0): a transient pre-exec identity reading must not be proof of PID reuse"
  else
    ok "PHASE 2: start.sh rc=0 - the transient pre-exec 'different' reading did NOT become a recycle verdict"
  fi

  # Put a regular file back at the log path: the JVM's fd still points at the FIFO inode,
  # and nothing after this should ever be able to block on reading it.
  rm -f "${T}/home/logs/cm-insight.out"
  cp -f "${T}/drained.log" "${T}/home/logs/cm-insight.out" 2>/dev/null

  _tpid="$(head -n 1 "${T}/home/run/cm-insight.pid" 2>/dev/null)"
  case "${_tpid}" in
    ''|*[!0-9]*) bad "PHASE 2: no usable PID file was published for the instance that started through the launch transition" ;;
    *)
      _tident="$(pid_identity "${_tpid}")"
      if [ "${_tident}" = "0" ]; then
        ok "PHASE 2: the published PID ${_tpid} IS the CM Insight process (ci_proc_identity=0) - the identity settled"
      else
        bad "PHASE 2: PID file holds PID ${_tpid} with ci_proc_identity=${_tident} (0 = CM Insight)"
      fi
      ;;
  esac

  cm_run "${T}/home" status.sh > "${T}/status.log" 2>&1
  _tstat=$?
  if [ "${_tstat}" -eq 0 ] && grep -q '^Status: RUNNING' "${T}/status.log" && grep -q '^Health: OK' "${T}/status.log"; then
    ok "PHASE 2: status.sh reports Status: RUNNING and Health: OK for the transition-started instance"
  else
    bad "PHASE 2: status.sh (rc=${_tstat}) did not report RUNNING + Health: OK"
  fi
  raw "${T}/status.log"

  cm_run "${T}/home" stop.sh --timeout 20 > "${T}/stop.log" 2>&1
  _tstop=$?
  if [ "${_tstop}" -eq 0 ]; then
    ok "PHASE 2: stop.sh rc=0 - the instance started through the launch transition is tracked and stoppable"
  else
    bad "PHASE 2: stop.sh exited ${_tstop} (expected 0)"
  fi
  raw "${T}/stop.log"
  all_stopped_and_silent "${T}/home" "PHASE 2"
  kill "${READER}" 2>/dev/null
fi

# ===========================================================================
# PHASE 3 - a foreign live process is never signalled (the grace must not weaken this)
# ===========================================================================
note "PHASE 3/4: a PID file holding an unrelated live process must never be signalled"
F="${WORK}/foreign"
if ! make_home "${F}/home"; then
  bad "PHASE 3: could not build the isolated home under ${F}/home"
else
  sleep 60 &
  FOREIGN_PID=$!
  EXTRA_PIDS="${EXTRA_PIDS} ${FOREIGN_PID}"
  printf '%s\n' "${FOREIGN_PID}" > "${F}/home/run/cm-insight.pid"
  ok "PHASE 3: PID file holds PID ${FOREIGN_PID} ('sleep 60'), an unrelated live process"
  cm_run "${F}/home" stop.sh --timeout 5 > "${F}/stop.log" 2>&1
  _f_rc=$?
  raw "${F}/stop.log"
  if [ "${_f_rc}" -eq 0 ]; then
    ok "PHASE 3: stop.sh rc=0 (it reported that CM Insight is not running)"
  else
    bad "PHASE 3: stop.sh exited ${_f_rc} for a foreign PID file (expected 0: nothing to stop, nothing to signal)"
  fi
  if grep -q 'different process' "${F}/stop.log"; then
    ok "PHASE 3: stop.sh named the truth - the PID file points at a DIFFERENT process"
  else
    bad "PHASE 3: stop.sh did not report a different process for the foreign PID"
  fi
  if kill -0 "${FOREIGN_PID}" 2>/dev/null; then
    ok "PHASE 3: the unrelated live process was NOT signalled (it is still alive)"
  else
    bad "PHASE 3: the unrelated process was signalled/terminated - ownership safety was weakened"
  fi
  if [ -f "${F}/home/run/cm-insight.pid" ]; then
    bad "PHASE 3: the stale PID file was not removed"
  else
    ok "PHASE 3: the stale PID file was removed"
  fi
  kill "${FOREIGN_PID}" 2>/dev/null
fi

# ===========================================================================
# PHASE 4 - marker-to-socket tie across the mapped/plain spellings (sections C and D)
# ===========================================================================
note "PHASE 4/4: the exact marker tied to a socket the table spells IPv4-mapped IPv6"
if ci_socket_host_matches '::ffff:127.0.0.1' '127.0.0.1'; then
  ok "PHASE 4: the CI evidence's row spelling ::ffff:127.0.0.1 is TIED to the configured 127.0.0.1 (ci_socket_host_matches)"
else
  bad "PHASE 4: the mapped spelling ::ffff:127.0.0.1 is not tied to the configured 127.0.0.1 - raw string equality is back"
fi
if ci_socket_host_matches '[::ffff:127.0.0.1]' '127.0.0.1'; then
  ok "PHASE 4: the bracketed tool spelling [::ffff:127.0.0.1] is tied to 127.0.0.1 as well"
else
  bad "PHASE 4: the bracketed spelling [::ffff:127.0.0.1] is not tied to 127.0.0.1"
fi
if ci_socket_host_matches '::ffff:192.0.2.10' '127.0.0.1'; then
  bad "PHASE 4: an unrelated mapped address (::ffff:192.0.2.10) was tied to the loopback bind 127.0.0.1 - the tie must not widen"
else
  ok "PHASE 4: the unrelated mapped address ::ffff:192.0.2.10 is NOT tied to 127.0.0.1 (the tie does not widen an ownership claim)"
fi

M="${WORK}/mapped"
if ! make_home "${M}/home"; then
  bad "PHASE 4: could not build the isolated home under ${M}/home"
else
  # A marker listener whose argv does NOT identify CM Insight, bound to the IPv4-mapped IPv6
  # address: the only admissible ownership evidence is the marker-to-socket tie. Bound with a
  # raw 16-byte mapped address on an INET6 channel because InetAddress.getByName("::ffff:...")
  # normalises to an Inet4Address (which would bind the plain IPv4 socket and hide the case).
  cat > "${M}/MappedMarker.java" <<'JAVA'
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Test fixture for tests/shell/lifecycle_identity_race_test.sh (PHASE 4). Serves the exact
 * CM Insight health marker on a socket the kernel reports as ::ffff:127.0.0.1, so the
 * marker-to-socket tie in bin/stop.sh has to compare socket-equivalent spellings. Its own
 * command line deliberately does NOT name cm-insight.jar, bin/cm-insight or the main class,
 * so argv identity can never substitute for the tie.
 */
public class MappedMarker {
  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    byte[] raw = new byte[16];
    raw[10] = (byte) 0xff;
    raw[11] = (byte) 0xff;
    raw[12] = 127;
    raw[13] = 0;
    raw[14] = 0;
    raw[15] = 1;
    InetAddress mapped = InetAddress.getByAddress(null, raw);
    ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.INET6);
    server.bind(new InetSocketAddress(mapped, port), 16);
    System.out.println("MappedMarker listening on " + server.getLocalAddress() + " port " + port);
    System.out.flush();
    String body = "{\"status\":\"UP\",\"service\":\"cm-insight\"}";
    byte[] payload = (body
        + "\r\n").getBytes(StandardCharsets.UTF_8);
    String headers = "HTTP/1.1 200 OK\r\n"
        + "Content-Type: application/json\r\n"
        + "Content-Length: " + payload.length + "\r\n"
        + "Connection: close\r\n\r\n";
    while (true) {
      try (SocketChannel ch = server.accept()) {
        ByteBuffer request = ByteBuffer.allocate(4096);
        ch.read(request);
        ch.write(ByteBuffer.wrap(headers.getBytes(StandardCharsets.UTF_8)));
        ch.write(ByteBuffer.wrap(payload));
      } catch (Exception ignored) {
        // a test fixture: a dropped connection must not end the listener
      }
    }
  }
}
JAVA

  ( cd "${M}" && exec "${JAVA_BIN}" "${M}/MappedMarker.java" "${PORT}" ) > "${M}/listener.log" 2>&1 &
  LISTENER_PID=$!
  EXTRA_PIDS="${EXTRA_PIDS} ${LISTENER_PID}"

  _w=0
  while [ "${_w}" -lt 150 ]; do
    if ci_health_marker_ok "$(ci_health_url 127.0.0.1 "${PORT}")"; then break; fi
    if ! kill -0 "${LISTENER_PID}" 2>/dev/null; then break; fi
    sleep 0.2
    _w=$(( _w + 1 ))
  done
  if ci_health_marker_ok "$(ci_health_url 127.0.0.1 "${PORT}")"; then
    ok "PHASE 4: the fixture listener answers the EXACT CM Insight marker on 127.0.0.1:${PORT}"
  else
    bad "PHASE 4: the fixture listener did not answer the exact marker; see ${M}/listener.log"
    raw "${M}/listener.log"
  fi

  _row_hosts="$(ci_listener_rows "${PORT}" | awk -F'|' '{print $1}' | sort -u | tr '\n' ' ')"
  case "${_row_hosts}" in
    *::ffff:*)
      ok "PHASE 4: the socket table reports the listener as '${_row_hosts}' - the IPv4-mapped IPv6 form of the CI evidence, not the plain spelling"
      ;;
    *)
      bad "PHASE 4: the fixture bound an IPv4-mapped IPv6 socket but the table reports '${_row_hosts:-<nothing>}'; the mapped case is not being exercised"
      ;;
  esac

  _l_ident="$(pid_identity "${LISTENER_PID}")"
  if [ "${_l_ident}" = "1" ]; then
    ok "PHASE 4: the fixture's command line does NOT identify CM Insight (ci_proc_identity=1), so ONLY the marker-to-socket tie can authorise stopping it"
  else
    bad "PHASE 4: the fixture's identity came out as ${_l_ident} (expected 1 = a different process); the argv-identity path would mask the tie"
  fi

  cm_run "${M}/home" stop.sh --untracked --timeout 10 > "${M}/stop.log" 2>&1
  _m_rc=$?
  raw "${M}/stop.log"
  if [ "${_m_rc}" -eq 0 ]; then
    ok "PHASE 4: stop.sh --untracked rc=0 - the mapped row was attributed and the instance was stopped"
  else
    bad "PHASE 4: stop.sh --untracked exited ${_m_rc} (expected 0): the marker could not be tied to the mapped socket row"
  fi
  if grep -q 'exact CM Insight health marker answered' "${M}/stop.log"; then
    ok "PHASE 4: the stated ownership evidence is the exact marker tied to that socket (section D's requirement)"
  else
    bad "PHASE 4: stop.sh stopped it without naming the marker-to-socket evidence"
  fi
  if ci_health_marker_ok "$(ci_health_url 127.0.0.1 "${PORT}")"; then
    bad "PHASE 4: the fixture listener still answers the marker after --untracked stop"
  else
    ok "PHASE 4: the fixture listener is gone and 127.0.0.1:${PORT} answers nothing"
  fi
  kill "${LISTENER_PID}" 2>/dev/null
fi

summary
