#!/usr/bin/env bash
#
# CM Insight - committed regression test: exact health marker, structural argv
# identity, and "an unrelated process/JVM is never an ownership proof"
# (Goal 01B sections F and D; the rules themselves are Goal 01A section H).
#
#   ./tests/shell/marker_identity_safety_test.sh        # exit 0 = pass, non-zero = fail
#
# Self-contained, offline and dependency-free:
#   * phases 1-4 need nothing but bash and coreutils;
#   * phase 5 needs a JDK on PATH or JAVA_HOME and is skipped, with an explicit
#     "skip:" line, when there is none (CI always has one via actions/setup-java);
#   * nothing here binds the repository's conf/application.properties, run/ or logs/:
#     every script invocation uses an isolated temporary configuration, PID directory
#     and log directory, and the temporary tree is removed by an EXIT trap that also
#     kills any fixture process this test started, on success and on failure alike.
#
# What it pins (each assertion prints "ok:"/"FAIL:"):
#   1. the health marker is an EXACT comparison, never a substring match;
#   2. identity is structural over argv tokens: an option value, a positional
#      argument, a bare class-path entry or a lookalike main class is never identity;
#   3. a live unrelated process (including one whose argv[0] is literally "java")
#      is never reported as CM Insight;
#   4. a PID file that tracks a live FOREIGN process is never signalled, by
#      stop.sh, by stop.sh --untracked, or reported healthy by status.sh;
#   5. a real unrelated JVM holding the configured port is refused by start.sh and
#      never signalled by stop.sh --untracked.

set -uo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")/../.." && pwd)" || {
  printf 'FAIL: cannot resolve the repository root\n' >&2
  exit 2
}
LIB="${REPO_ROOT}/bin/lib/cm-insight-lifecycle.sh"
if [ ! -r "${LIB}" ]; then
  printf 'FAIL: missing %s\n' "${LIB}" >&2
  exit 2
fi
# shellcheck source=/dev/null
. "${LIB}"

TOTAL=0
FAILED=0
declare_pass() { TOTAL=$((TOTAL + 1)); printf 'ok: %s\n' "$1"; }
declare_fail() { TOTAL=$((TOTAL + 1)); FAILED=$((FAILED + 1)); printf 'FAIL: %s\n' "$1"; }

# ---------------------------------------------------------------------------
# phase 1 - exact health marker
# ---------------------------------------------------------------------------
printf -- '--- phase 1: exact health marker ---\n'
MARKER='{"status":"UP","service":"cm-insight"}'

marker_accepted() {
  # $1 = label, $2 = body that MUST be the marker
  if ci_health_body_is_marker "$2"; then declare_pass "$1"; else declare_fail "$1 (the exact marker was rejected)"; fi
}
marker_rejected() {
  # $1 = label, $2 = body that must NOT be accepted
  if ci_health_body_is_marker "$2"; then
    declare_fail "$1 (a non-marker body was accepted: $(printf '%q' "$2"))"
  else
    declare_pass "$1"
  fi
}

marker_accepted "the exact marker is accepted" "${MARKER}"
marker_accepted "the marker with surrounding whitespace is accepted" "  ${MARKER}
"
marker_accepted "the marker with internal whitespace is accepted" '{ "status" : "UP" , "service" : "cm-insight" }'
marker_rejected "an extra field is rejected" '{"status":"UP","service":"cm-insight","note":"this is not cm insight"}'
marker_rejected "reordered fields are rejected" '{"service":"cm-insight","status":"UP"}'
marker_rejected "a lookalike service name is rejected" '{"status":"UP","service":"cm-insight-alike"}'
marker_rejected "another service name is rejected" '{"status":"UP","service":"tomcat"}'
marker_rejected "a DOWN status is rejected" '{"status":"DOWN","service":"cm-insight"}'
marker_rejected "a lower-case status is rejected" '{"status":"up","service":"cm-insight"}'
marker_rejected "the marker nested in a larger document is rejected" '{"outer":{"status":"UP","service":"cm-insight"}}'
marker_rejected "the marker with trailing text is rejected" "${MARKER} and more"
marker_rejected "a plain OK body is rejected" 'OK'
marker_rejected "an empty body is rejected" ''
marker_rejected "an HTML page containing the fields is rejected" "<html>${MARKER}</html>"

# ---------------------------------------------------------------------------
# phase 2 - structural argv identity
# ---------------------------------------------------------------------------
printf -- '--- phase 2: structural argv identity ---\n'

argv_accepted() {
  # $1 = label, then the argv tokens
  if printf '%s\n' "${@:2}" | ci_argv_is_cm_insight; then declare_pass "$1"; else declare_fail "$1 (a real CM Insight command line was rejected)"; fi
}
argv_rejected() {
  # $1 = label, then the argv tokens
  if printf '%s\n' "${@:2}" | ci_argv_is_cm_insight; then
    declare_fail "$1 (an unrelated command line was accepted as CM Insight)"
  else
    declare_pass "$1"
  fi
}

argv_accepted "the real launcher command line is identity" \
  java -Dcminsight.home=/srv/cminsight -cp /srv/cminsight/build/cm-insight.jar \
  com.mraibo.cminsight.app.Main --config /srv/cminsight/conf/application.properties
argv_accepted "the exact main class alone is identity" com.mraibo.cminsight.app.Main
argv_accepted "the bin/cm-insight launcher token is identity" /srv/cminsight/bin/cm-insight
argv_accepted "the target of -jar with basename cm-insight.jar is identity" java -jar /srv/cminsight/build/cm-insight.jar
argv_rejected "an OPTION VALUE naming the jar is not identity" \
  java -Dreview.archive.name=cm-insight.jar -cp /tmp/unrelated.jar com.example.Main
argv_rejected "a positional jar-looking argument is not identity" \
  java -cp /tmp/other.jar com.example.Main /tmp/cm-insight.jar
argv_rejected "a positional argument named exactly cm-insight.jar is not identity" \
  java -cp /tmp/other.jar FakeHttpService cm-insight.jar
argv_rejected "a bare class-path entry named like our jar is not identity" \
  java -cp cm-insight.jar com.example.Main
argv_rejected "an unrelated JVM is not identity" java -Xmx512m -cp /tmp/other.jar com.example.Main
argv_rejected "another program in bin/ is not identity" /srv/unrelated/bin/cm-insight-helper
argv_rejected "a lookalike main class is not identity" com.mraibo.cminsight.app.MainHelper
argv_rejected "a package-prefixed main class is not identity" com.example.com.mraibo.cminsight.app.Main
argv_rejected "an empty command line is not identity" ''

# ---------------------------------------------------------------------------
# phase 3 - a live unrelated process is never CM Insight
# ---------------------------------------------------------------------------
printf -- '--- phase 3: live unrelated processes ---\n'

UNRELATED_PIDS=""
cleanup_unrelated() {
  # Never leave a fixture behind (called from the EXIT trap and by the phases).
  for _p in ${UNRELATED_PIDS}; do
    kill -TERM "${_p}" 2>/dev/null || true
  done
  [ -n "${UNRELATED_PIDS}" ] && sleep 1
  for _p in ${UNRELATED_PIDS}; do
    kill -KILL "${_p}" 2>/dev/null || true
  done
  UNRELATED_PIDS=""
}

# A process whose argv[0] is literally "java": the strongest form of the rule
# "a process is never signalled merely because it is a JVM".
bash -c 'exec -a java sleep 300' 2>/dev/null &
JVM_ARGV0_PID=$!
sleep 300 &
PLAIN_SLEEP_PID=$!
UNRELATED_PIDS="${JVM_ARGV0_PID} ${PLAIN_SLEEP_PID}"
sleep 1

check_not_identity() {
  # $1 = label, $2 = pid
  ci_proc_identity "$2"
  _rc=$?
  if [ "${_rc}" -eq 0 ]; then
    declare_fail "$1 (reported as CM Insight, rc=0)"
  else
    declare_pass "$1 (identity rc=${_rc}, never a proof)"
  fi
}

if kill -0 "${JVM_ARGV0_PID}" 2>/dev/null; then
  check_not_identity "a process with argv[0]=java is not CM Insight" "${JVM_ARGV0_PID}"
else
  declare_fail "the argv[0]=java fixture did not start"
fi
if kill -0 "${PLAIN_SLEEP_PID}" 2>/dev/null; then
  check_not_identity "an unrelated process is not CM Insight" "${PLAIN_SLEEP_PID}"
else
  declare_fail "the unrelated-process fixture did not start"
fi
cleanup_unrelated

# ---------------------------------------------------------------------------
# phase 4 - a tracked FOREIGN PID is never signalled
# ---------------------------------------------------------------------------
printf -- '--- phase 4: a tracked foreign PID is never signalled ---\n'

# Isolated configuration/run/log tree: the repository's conf/, run/ and logs/ are
# never touched, and with no listener on an unused port every probe is a local
# refusal (offline, no dependency on the application being built).
ISOLATED="$(mktemp -d)" || { printf 'FAIL: mktemp -d failed\n' >&2; exit 2; }
FIXTURE_PIDS=""
PHASE5_PORT=""
cleanup_all() {
  # Runs on every exit path: kill every fixture this test started, then remove the
  # isolated tree. Bounded: SIGTERM, 1s, SIGKILL.
  if [ -n "${FIXTURE_PIDS}" ]; then
    for _p in ${FIXTURE_PIDS}; do kill -TERM "${_p}" 2>/dev/null || true; done
    sleep 1
    for _p in ${FIXTURE_PIDS}; do kill -KILL "${_p}" 2>/dev/null || true; done
    FIXTURE_PIDS=""
  fi
  [ -n "${ISOLATED}" ] && rm -rf "${ISOLATED}" 2>/dev/null || true
}
trap cleanup_all EXIT

free_loopback_port() {
  # $1 = first candidate; prints the first candidate that nothing answers on.
  # /dev/tcp is a bash builtin, so no dependency is added; connect refusal is the
  # portable "nobody is listening (or bound only on another family)" test.
  _cand="$1"
  _tries=0
  while [ "${_tries}" -lt 60 ]; do
    if ! (exec 3<>"/dev/tcp/127.0.0.1/${_cand}") 2>/dev/null; then
      printf '%s' "${_cand}"
      return 0
    fi
    _cand=$((_cand + 1))
    _tries=$((_tries + 1))
  done
  return 1
}

DEAD_PORT="$(free_loopback_port $((30000 + RANDOM % 20000)))" || { printf 'FAIL: no free loopback port found\n' >&2; exit 2; }
mkdir -p "${ISOLATED}/f4/run" "${ISOLATED}/f4/logs" "${ISOLATED}/f4/conf"
cat > "${ISOLATED}/f4/conf/application.properties" <<CFG
web.bind=127.0.0.1
web.port=${DEAD_PORT}
CFG

run_isolated() {
  # Runs a bin/ script against the isolated configuration, with the isolated PID
  # and log directories, and prints its combined output.
  _script="$1"
  shift
  CM_INSIGHT_CONFIG="${ISOLATED}/f4/conf/application.properties" \
  CM_INSIGHT_RUN_DIR="${ISOLATED}/f4/run" \
  CM_INSIGHT_LOG_DIR="${ISOLATED}/f4/logs" \
    "${REPO_ROOT}/bin/${_script}" "$@" 2>&1
}

FOREIGN_PID=""
for attempt in 1 2 3; do
  sleep 300 &
  FOREIGN_PID=$!
  FIXTURE_PIDS="${FIXTURE_PIDS} ${FOREIGN_PID}"
  sleep 1
  kill -0 "${FOREIGN_PID}" 2>/dev/null && break
  FOREIGN_PID=""
done
if [ -z "${FOREIGN_PID}" ]; then
  declare_fail "could not start the foreign-process fixture"
else
  printf '%s\n' "${FOREIGN_PID}" > "${ISOLATED}/f4/run/cm-insight.pid"

  OUT4="$(run_isolated stop.sh)"; RC4=$?
  if [ "${RC4}" -eq 0 ]; then declare_pass "stop.sh exits 0 for a foreign tracked PID"; else declare_fail "stop.sh exited ${RC4} for a foreign tracked PID (expected 0)"; fi
  if kill -0 "${FOREIGN_PID}" 2>/dev/null; then declare_pass "stop.sh did NOT signal the foreign tracked PID"; else declare_fail "stop.sh signalled the foreign tracked PID"; fi
  if [ -f "${ISOLATED}/f4/run/cm-insight.pid" ]; then declare_fail "stop.sh left the unusable PID file in place"; else declare_pass "stop.sh removed the unusable PID file"; fi
  printf '%s\n' "${OUT4}" | sed 's/^/    | /'

  printf '%s\n' "${FOREIGN_PID}" > "${ISOLATED}/f4/run/cm-insight.pid"
  OUT4B="$(run_isolated stop.sh --untracked)"; RC4B=$?
  if kill -0 "${FOREIGN_PID}" 2>/dev/null; then
    declare_pass "stop.sh --untracked did NOT signal the foreign tracked PID (exit ${RC4B})"
  else
    declare_fail "stop.sh --untracked signalled the foreign tracked PID"
  fi

  printf '%s\n' "${FOREIGN_PID}" > "${ISOLATED}/f4/run/cm-insight.pid"
  OUT4C="$(run_isolated status.sh)"; RC4C=$?
  if [ "${RC4C}" -ne 0 ]; then declare_pass "status.sh exits non-zero for a foreign tracked PID"; else declare_fail "status.sh exited 0 for a foreign tracked PID"; fi
  if printf '%s' "${OUT4C}" | grep -q 'Health: OK'; then
    declare_fail "status.sh reported the foreign tracked PID as healthy (Health: OK)"
  else
    declare_pass "status.sh never reports a foreign tracked PID as healthy"
  fi
  printf '%s\n' "${OUT4C}" | sed 's/^/    | /'
  rm -f "${ISOLATED}/f4/run/cm-insight.pid"
fi

# ---------------------------------------------------------------------------
# phase 5 - a real unrelated JVM holding the configured port is refused
# ---------------------------------------------------------------------------
printf -- '--- phase 5: an unrelated JVM on the configured port ---\n'

java_bin() {
  if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "${JAVA_HOME}/bin/java" ] && { printf '%s' "${JAVA_HOME}/bin/java"; return 0; }
    [ -x "${JAVA_HOME}/bin/java.exe" ] && { printf '%s' "${JAVA_HOME}/bin/java.exe"; return 0; }
  fi
  command -v java 2>/dev/null || true
}

JAVA="$(java_bin)"
if [ -z "${JAVA}" ]; then
  printf 'skip: phase 5 needs a JDK (JAVA_HOME or java on PATH); the ownership rule it\n'
  printf 'skip: checks is covered for ordinary processes by phases 3 and 4\n'
else
  PHASE5_PORT="$(free_loopback_port $((31000 + RANDOM % 20000)))" || PHASE5_PORT=""
  if [ -z "${PHASE5_PORT}" ]; then
    declare_fail "phase 5 could not find a free loopback port"
  else
    mkdir -p "${ISOLATED}/f5/run" "${ISOLATED}/f5/logs" "${ISOLATED}/f5/conf"
    cat > "${ISOLATED}/f5/conf/application.properties" <<CFG
web.bind=127.0.0.1
web.port=${PHASE5_PORT}
CFG
    cat > "${ISOLATED}/f5/UnrelatedFixture.java" <<'JAVA'
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** A deliberately unrelated HTTP service: it is NOT CM Insight and must never be killed. */
public class UnrelatedFixture {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (ServerSocket server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("unrelated fixture listening on 127.0.0.1:" + port);
            System.out.flush();
            while (true) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[4096];
                    try {
                        in.read(buf);
                    } catch (Exception ignored) {
                        // no request body of interest
                    }
                    byte[] body = "{\"status\":\"UP\",\"service\":\"unrelated-service\"}".getBytes("UTF-8");
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    out.write(body);
                    out.flush();
                } catch (Exception ignored) {
                    // keep serving
                }
            }
        }
    }
}
JAVA
    "${JAVA}" "${ISOLATED}/f5/UnrelatedFixture.java" "${PHASE5_PORT}" \
      > "${ISOLATED}/f5/fixture.out" 2>&1 &
    JAVA_FIXTURE_PID=$!
    FIXTURE_PIDS="${FIXTURE_PIDS} ${JAVA_FIXTURE_PID}"

    WAITED=0
    UP=false
    while [ "${WAITED}" -lt 30 ]; do
      if (exec 3<>"/dev/tcp/127.0.0.1/${PHASE5_PORT}") 2>/dev/null; then UP=true; break; fi
      sleep 1
      WAITED=$((WAITED + 1))
    done
    if [ "${UP}" != true ]; then
      declare_fail "the unrelated JVM fixture did not listen on 127.0.0.1:${PHASE5_PORT} within 30s"
      sed 's/^/    | /' "${ISOLATED}/f5/fixture.out" 2>/dev/null | tail -n 10
    else
      declare_pass "the unrelated JVM fixture is listening on 127.0.0.1:${PHASE5_PORT}"

      run_isolated_f5() {
        _script="$1"
        shift
        CM_INSIGHT_CONFIG="${ISOLATED}/f5/conf/application.properties" \
        CM_INSIGHT_RUN_DIR="${ISOLATED}/f5/run" \
        CM_INSIGHT_LOG_DIR="${ISOLATED}/f5/logs" \
          "${REPO_ROOT}/bin/${_script}" "$@" 2>&1
      }

      OUT5="$(run_isolated_f5 start.sh --timeout 10)"; RC5=$?
      if [ "${RC5}" -ne 0 ]; then
        declare_pass "start.sh refuses to start while an unrelated JVM holds the port (exit ${RC5})"
      else
        declare_fail "start.sh started an instance although an unrelated JVM holds the port"
      fi
      printf '%s' "${OUT5}" | grep -qiE 'refusing to start|already bound|already serving' \
        && declare_pass "start.sh names the port conflict instead of silently proceeding" \
        || declare_fail "start.sh did not name the port conflict"
      printf '%s\n' "${OUT5}" | sed 's/^/    | /'
      if kill -0 "${JAVA_FIXTURE_PID}" 2>/dev/null; then
        declare_pass "start.sh did not touch the unrelated JVM"
      else
        declare_fail "start.sh killed the unrelated JVM"
      fi

      # Is the foreign listener attributable to a PID on this platform? Which refusal
      # stop.sh must produce depends on that, and both outcomes must leave it alive.
      ATTRIBUTABLE=false
      while IFS='|' read -r _h _p _c; do
        case "${_p}" in ''|-|*[!0-9]*) continue ;; esac
        case "${_c}" in foreign|sibling) continue ;; esac
        ATTRIBUTABLE=true
      done < <(ci_bind_listener_rows "${PHASE5_PORT}" 127.0.0.1)

      OUT5B="$(run_isolated_f5 stop.sh --untracked --force --timeout 10)"; RC5B=$?
      if kill -0 "${JAVA_FIXTURE_PID}" 2>/dev/null; then
        declare_pass "stop.sh --untracked never signalled the unrelated JVM (exit ${RC5B})"
      else
        declare_fail "stop.sh --untracked signalled the unrelated JVM"
      fi
      if (exec 3<>"/dev/tcp/127.0.0.1/${PHASE5_PORT}") 2>/dev/null; then
        declare_pass "the unrelated JVM is still serving after the stop attempt"
      else
        declare_fail "the unrelated JVM stopped serving after the stop attempt"
      fi
      if [ "${ATTRIBUTABLE}" = true ]; then
        if [ "${RC5B}" -ne 0 ] && printf '%s' "${OUT5B}" | grep -q 'REFUSING'; then
          declare_pass "stop.sh --untracked refuses an attributable foreign listener (exit ${RC5B})"
        else
          declare_fail "stop.sh --untracked did not refuse an attributable foreign listener (exit ${RC5B})"
        fi
      else
        printf 'skip: this platform does not attribute the listener to a PID, so the refusal half\n'
        printf 'skip: of the stop.sh check cannot be asserted here (the no-signal half still holds)\n'
      fi
      printf '%s\n' "${OUT5B}" | sed 's/^/    | /'
    fi
    kill -TERM "${JAVA_FIXTURE_PID}" 2>/dev/null || true
    sleep 1
    kill -KILL "${JAVA_FIXTURE_PID}" 2>/dev/null || true
  fi
fi

# ---------------------------------------------------------------------------
# verdict
# ---------------------------------------------------------------------------
printf '\n'
if [ "${FAILED}" -eq 0 ]; then
  printf 'PASS: %s (%s assertions, %ss)\n' "$(basename -- "${BASH_SOURCE[0]:-$0}")" "${TOTAL}" "${SECONDS}"
  exit 0
fi
printf 'FAIL: %s (%s of %s assertions failed, %ss)\n' "$(basename -- "${BASH_SOURCE[0]:-$0}")" "${FAILED}" "${TOTAL}" "${SECONDS}"
exit 1
