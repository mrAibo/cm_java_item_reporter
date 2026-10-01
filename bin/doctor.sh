#!/usr/bin/env bash
#
# CM Insight - environment doctor.
#
#   ./bin/doctor.sh [--strict] [--help]
#
# Checks the local environment before a build or a start. Nothing here touches
# the network and no credential value is ever printed: only the *names* of the
# environment variables / secret files a profile refers to.
#
# Credential, exposure and secret-file semantics are NOT re-implemented here. The
# doctor asks the application for them (com.mraibo.cminsight.app.ConfigCheck), so
# a configured-but-missing credential source is an ERROR here exactly when the
# runtime would fail closed, a non-loopback plain-HTTP bind is refused here
# exactly when the runtime would refuse it, and a `.file` reference that escapes
# secrets.dir - by `..`, or through a link or junction with an inside-looking
# name - is refused by the ONE containment rule the runtime applies
# (SecretResolver, real path). A shell `[ -r ... ]` probe used to answer that
# question here and said "readable" about exactly those escapes, which made the
# doctor more permissive than the runtime; it is gone. The configurable path base
# is the same application home the launcher passes as -Dcminsight.home.
#
# Message prefixes: "OK:  " pass, "WARN:" non-fatal problem, "ERROR:" failure.
# With --strict every warning is treated as a failure.
#
# Exit codes: 0 healthy (or healthy with warnings when not strict), 1 problems
# found, 2 usage error.

set -euo pipefail

STRICT=false
usage() {
  cat <<'USAGE'
Usage: ./bin/doctor.sh [--strict] [--help]

Checks, without network access:
  - java / javac / jar availability and the JDK major version (17+ required)
  - configuration file presence and readability
  - configuration semantics, DELEGATED to the application itself
    (com.mraibo.cminsight.app.ConfigCheck, section E parity): the credential
    sources, the web exposure policy and the repository profiles, with the
    runtime's own messages, plus the resolved operational paths (profiles.dir,
    classifications.file, secrets.dir, data.dir, reports.dir, logs.dir)
  - required writable directories (data, reports, logs, secrets, run)
  - repository profiles: loaded by the application's own loader (a profile it
    refuses is an ERROR, because a real start would fail) and every declared
    credential resolved by the application's own SecretResolver, so a secret file
    that escapes secrets.dir by `..`, link or junction is REFUSED here exactly as
    the runtime refuses it. Names only - environment variable names and secret
    file names, NEVER their values
  - curl availability for the HTTP health checks
  - optional library jars in lib/{ibm,db2,oracle,app}

Options:
  --strict   treat warnings as failures (exit 1 when any warning is present)

Environment:
  JAVA_HOME           JDK 17+ home (may be /c/tools/jdk17, C:/tools/jdk17, C:\tools\jdk17)
  CM_INSIGHT_CONFIG   alternative configuration file (default conf/application.properties);
                      a relative value is resolved against the application home
  CM_INSIGHT_HOME     application home used for relative operational paths. Default: the
                      repository root this script derives from its own location. It is
                      passed to the validator as -Dcminsight.home, so the doctor checks
                      exactly the paths the launcher would resolve
  CM_INSIGHT_RUN_DIR  explicit PID-file directory (default <application home>/run)
  CM_INSIGHT_LOG_DIR  explicit application-log directory (default <application home>/logs
                      or the configured logs.dir); both match bin/start.sh and bin/status.sh

Exit codes: 0 healthy, 1 problems found, 2 usage error.
USAGE
}

for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage; exit 0 ;;
    --strict) STRICT=true ;;
    *) printf 'ERROR: unknown argument: %s\n' "${arg}" >&2; usage >&2; exit 2 ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
[ -d "${SCRIPT_DIR}" ] || { printf 'ERROR: cannot locate script directory: %s\n' "${SCRIPT_DIR}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)" || { printf 'ERROR: cannot resolve repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

CONFIG="${CM_INSIGHT_CONFIG:-${ROOT}/conf/application.properties}"

# Application home (Goal 01A section D): the base for every RELATIVE operational
# path in the configuration, and the same value the launcher passes to the JVM as
# -Dcminsight.home. ROOT stays the installation root (scripts, build/, lib/), so a
# relocated home is still checked by the script that lives in the installation.
APP_HOME="${CM_INSIGHT_HOME:-${ROOT}}"
case "${CONFIG}" in
  /*|[A-Za-z]:[\\/]*) : ;;
  *) CONFIG="${APP_HOME}/${CONFIG}" ;;
esac

FAIL_COUNT=0
WARN_COUNT=0
ok()   { printf 'OK:    %s\n' "$*"; }
warn() { printf 'WARN:  %s\n' "$*" >&2; WARN_COUNT=$((WARN_COUNT + 1)); }
err()  { printf 'ERROR: %s\n' "$*" >&2; FAIL_COUNT=$((FAIL_COUNT + 1)); }

printf 'CM Insight doctor\n'
printf '=================\n'
printf 'root:   %s\n' "${ROOT}"
if [ "${APP_HOME}" = "${ROOT}" ]; then
  printf 'home:   %s\n' "${APP_HOME}"
else
  printf 'home:   %s (from CM_INSIGHT_HOME)\n' "${APP_HOME}"
fi
printf 'config: %s\n' "${CONFIG}"
if [ "${STRICT}" = true ]; then printf 'mode:   strict (warnings are failures)\n'; fi

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
# Shared address model (Goal 01A G/H, owned by bin/lib/cm-insight-addr.sh): the ONE
# loopback classifier. The doctor never grows a second one - the exposure DECISION
# belongs to the application (section 3), and this module supplies the CLASSIFICATION
# used for the informational bind line.
ADDR_LIB="${SCRIPT_DIR}/lib/cm-insight-addr.sh"
if [ -r "${ADDR_LIB}" ]; then
  # shellcheck source=lib/cm-insight-addr.sh
  . "${ADDR_LIB}"
fi

config_value() {
  # $1 = properties file, $2 = literal key; prints the effective value (or nothing).
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

resolve_dir() {
  # $1 = configured directory value; a relative value resolves against the
  # application home, exactly as AppPaths resolves it for the JVM (never against
  # the directory this doctor happened to be started from).
  case "$1" in
    '' ) printf '' ;;
    /*) printf '%s' "$1" ;;
    [A-Za-z]:[\\/]*) printf '%s' "$1" ;;
    *) printf '%s/%s' "${APP_HOME}" "$1" ;;
  esac
}

# ---------------------------------------------------------------------------
# 1. toolchain
# ---------------------------------------------------------------------------
JH="${JAVA_HOME:-}"
if [ -n "${JH}" ]; then
  case "$(uname -s 2>/dev/null || printf 'unknown')" in
    MINGW*|MSYS*|CYGWIN*)
      case "${JH}" in
        [A-Za-z]:[\\/]*)
          _drive="$(printf '%s' "${JH}" | cut -c1 | tr 'A-Z' 'a-z')"
          _rest="$(printf '%s' "${JH}" | cut -c3- | tr '\\' '/')"
          JH="/${_drive}${_rest}"
          ;;
      esac
      ;;
  esac
  case "${JH}" in */) JH="${JH%/}" ;; esac
  JAVA_BIN="${JH}/bin/java"; JAVAC_BIN="${JH}/bin/javac"; JAR_BIN="${JH}/bin/jar"
  TOOL_SOURCE="JAVA_HOME=${JH}"
else
  JAVA_BIN="$(command -v java 2>/dev/null || true)"
  JAVAC_BIN="$(command -v javac 2>/dev/null || true)"
  JAR_BIN="$(command -v jar 2>/dev/null || true)"
  TOOL_SOURCE="PATH"
fi

tool_major() {
  _out="$("$1" -version 2>&1 || true)"
  _first="${_out%%$'\n'*}"
  _num="${_first#* }"
  _num="${_num%%.*}"
  printf '%s' "${_num}" | tr -cd '0-9'
}

LOCAL_JDK_HINT=""
if [ -x "${ROOT}/.tools/jdk17/bin/javac" ]; then
  LOCAL_JDK_HINT=" A local JDK was found here: export JAVA_HOME=${ROOT}/.tools/jdk17"
fi

if [ -n "${JAVA_BIN}" ] && [ -x "${JAVA_BIN}" ]; then
  JAVA_LINE="$("${JAVA_BIN}" -version 2>&1 || true)"
  JAVA_LINE="${JAVA_LINE%%$'\n'*}"
  JAVA_MAJOR="$(tool_major "${JAVA_BIN}")"
  case "${JAVA_MAJOR}" in
    ''|*[!0-9]*) warn "java: version not parseable (${JAVA_LINE:-<empty>}) at ${JAVA_BIN}" ;;
    *) if [ "${JAVA_MAJOR}" -ge 17 ]; then
         ok "java: ${JAVA_LINE} (source: ${TOOL_SOURCE})"
       else
         err "java: JDK 17+ required, found '${JAVA_LINE}' at ${JAVA_BIN}; set JAVA_HOME to a JDK 17+ or put it first on PATH"
       fi ;;
  esac
else
  err "java: not found (source: ${TOOL_SOURCE}); install a JDK 17+ and set JAVA_HOME or PATH.${LOCAL_JDK_HINT}"
fi

if [ -n "${JAVAC_BIN}" ] && [ -x "${JAVAC_BIN}" ]; then
  JAVAC_LINE="$("${JAVAC_BIN}" -version 2>&1 || true)"
  JAVAC_LINE="${JAVAC_LINE%%$'\n'*}"
  JAVAC_MAJOR="$(tool_major "${JAVAC_BIN}")"
  case "${JAVAC_MAJOR}" in
    ''|*[!0-9]*) err "javac: version not parseable (${JAVAC_LINE:-<empty>}) at ${JAVAC_BIN}; install a JDK 17+ (Temurin 17 recommended)" ;;
    *) if [ "${JAVAC_MAJOR}" -ge 17 ]; then
         ok "javac: ${JAVAC_LINE} (source: ${TOOL_SOURCE})"
       else
         err "javac: JDK 17+ required, found '${JAVAC_LINE}' at ${JAVAC_BIN}; set JAVA_HOME to a JDK 17+ or put it first on PATH"
       fi ;;
  esac
else
  err "javac: not found (source: ${TOOL_SOURCE}); the build needs a full JDK 17+ (a JRE is not enough).${LOCAL_JDK_HINT}"
fi

if [ -n "${JAR_BIN}" ] && [ -x "${JAR_BIN}" ]; then
  ok "jar: ${JAR_BIN}"
else
  warn "jar: not found (source: ${TOOL_SOURCE}); ./build.sh cannot package build/cm-insight.jar without it"
fi

# ---------------------------------------------------------------------------
# 2. configuration file and path spelling
# ---------------------------------------------------------------------------
if [ -r "${CONFIG}" ]; then
  if [ -s "${CONFIG}" ]; then
    ok "config: readable (${CONFIG})"
  else
    err "config: ${CONFIG} is empty; copy conf/application.properties.example and set the required keys"
  fi
else
  err "config: missing or unreadable (${CONFIG}); create it with 'cp conf/application.properties.example conf/application.properties'"
fi

BIND="$(config_value "${CONFIG}" web.bind)"
BIND="${BIND:-127.0.0.1}"
PORT="$(config_value "${CONFIG}" web.port)"
PORT="${PORT:-8080}"
SECRETS_DIR_CFG="$(config_value "${CONFIG}" secrets.dir)"
SECRETS_DIR="$(resolve_dir "${SECRETS_DIR_CFG:-conf/secrets}")"

for key in profiles.dir classifications.file data.dir reports.dir logs.dir secrets.dir; do
  raw="$(config_value "${CONFIG}" "${key}")"
  case "${raw}" in
    *\\*)
      err "config: ${key}=${raw} contains a backslash; a single backslash is an escape character in .properties files, so the application reads a munged path. Use forward slashes (${key}=conf/profiles) or doubled backslashes."
      ;;
  esac
done

# ---------------------------------------------------------------------------
# 3. configuration semantics - delegated to the application (section E parity)
#
# The doctor does NOT keep a second copy of the credential rules. It runs
# com.mraibo.cminsight.app.ConfigCheck, which calls the same AppConfig,
# SecretResolver, WebAuthSettings and SecurityPolicy code the runtime calls, and
# maps the application's own lines onto its own counters:
#   * a configured web.auth.user*/password* source is authoritative: a missing
#     environment variable or secret file is an ERROR, even on loopback - it is
#     NOT a fallback to admin/admin;
#   * an .env source wins over a .file source, so a readable file never rescues an
#     environment variable that is configured but unset;
#   * with no source configured at all, the admin/admin development default is
#     allowed on loopback (and warned about) and refused beyond loopback;
#   * a non-loopback plain-HTTP bind is refused unless web.allowInsecureHttp=true
#     is set explicitly, and that opt-in never unlocks admin/admin;
#   * repository profiles: the runtime's own loader decides which profiles exist
#     (a profile it refuses is an ERROR - Main exits 3 on the same exception) and
#     the runtime's own SecretResolver decides whether each declared credential
#     resolves. A `.file` reference that escapes secrets.dir by `..`, or through a
#     link/junction with an inside-looking name, is refused here with the
#     resolver's own sentence; the doctor never answers that question with a shell
#     `[ -r ... ]` test again, because that followed the link and reported such an
#     escape as "readable" (the section E defect this replaced).
# The validator also prints the resolved operational paths (section D), so the
# doctor and the launcher cannot disagree about which directory a relative
# profiles.dir / classifications.file / secrets.dir names.
#
# DELIBERATE ASYMMETRY (recorded, not an oversight - Goal 01A review observation):
# a web credential that is configured but cannot be resolved is an ERROR because it
# gates a real security boundary (who may bind the listener and authenticate to it),
# while an unresolvable repository credential is only a WARNING, because repository
# activation is METADATA-ONLY in Goal 01: there is no IBM CM adapter yet, no
# connection is attempted and no session is opened, so nothing is exposed by starting
# the runtime with an unresolved repository secret. The profile itself must still LOAD
# (a profile the loader refuses stays an ERROR, matching Main's exit 3). This asymmetry
# becomes a startup gate in the goal that actually connects to a repository.
# ---------------------------------------------------------------------------
VALIDATOR_CLASS="com.mraibo.cminsight.app.ConfigCheck"
VALIDATOR_CP=""
if [ -f "${ROOT}/build/classes/com/mraibo/cminsight/app/ConfigCheck.class" ]; then
  VALIDATOR_CP="${ROOT}/build/classes"
elif [ -f "${ROOT}/build/cm-insight.jar" ]; then
  VALIDATOR_CP="${ROOT}/build/cm-insight.jar"
fi

if [ ! -r "${CONFIG}" ]; then
  # Section 2 already reported this; without a readable file there is nothing to validate.
  :
elif [ -z "${VALIDATOR_CP}" ]; then
  warn "config: the configuration validator is not built yet (${ROOT}/build/classes); run ./build.sh, then re-run this doctor to check credential and exposure semantics"
elif [ -z "${JAVA_BIN}" ] || [ ! -x "${JAVA_BIN}" ]; then
  warn "config: java is not usable (${JAVA_BIN:-none}); the credential and exposure check was skipped - the java failure above is the real problem"
else
  set +e
  VALIDATOR_OUT="$("${JAVA_BIN}" -Dcminsight.home="${APP_HOME}" -cp "${VALIDATOR_CP}" "${VALIDATOR_CLASS}" --config "${CONFIG}" 2>&1)"
  VALIDATOR_RC=$?
  set -e
  case "${VALIDATOR_OUT}" in
    *ClassNotFoundException*|*NoClassDefFoundError*)
      warn "config: ${VALIDATOR_CP} predates ${VALIDATOR_CLASS}; re-run ./build.sh to restore doctor/runtime parity"
      ;;
    *)
      # Prefixes are the contract: an ERROR line is one of the runtime's own refusal reasons and
      # counts as a failure, a WARN line is one of its own warnings and counts as a warning.
      while IFS= read -r line; do
        case "${line}" in
          '') : ;;
          "ERROR: "*) err "${line#ERROR: }" ;;
          "WARN:  "*) warn "${line#WARN:  }" ;;
          "OK:    "*) printf 'OK:    %s\n' "${line#OK:    }" ;;
          "RESULT: "*) : ;;
          *) printf '%s\n' "${line}" ;;
        esac
      done <<< "${VALIDATOR_OUT}"
      # 0 = accepted, 1 = refused (its ERROR lines above already counted); anything else means the
      # validator itself failed, which must not be mistaken for a pass.
      case "${VALIDATOR_RC}" in
        0|1) : ;;
        *) err "config: ${VALIDATOR_CLASS} exited with code ${VALIDATOR_RC}; the configuration was not fully checked" ;;
      esac
      ;;
  esac
fi

# Informational echo only. Whether this bind is acceptable was decided by the application above;
# the classification itself comes from the shared address module so the doctor never grows a
# second, subtly different loopback test.
if declare -f ci_addr_is_loopback >/dev/null 2>&1; then
  if ci_addr_is_loopback "${BIND}"; then
    ok "bind: ${BIND} port ${PORT} (loopback literal)"
  else
    ok "bind: ${BIND} port ${PORT} (not a loopback literal: the application requires resolvable credentials and, for plain HTTP, the explicit insecure opt-in)"
  fi
else
  ok "bind: ${BIND} port ${PORT}"
fi

# ---------------------------------------------------------------------------
# 4. required writable directories
#
# Every operational directory is resolved against the application home
# (resolve_dir), so a relocated CM_INSIGHT_HOME gives consistent advice here and
# in the lifecycle scripts: bin/start.sh, bin/status.sh and bin/stop.sh default
# their PID/log directories to <home>/run and <home>/logs exactly as this section
# defaults them, and their explicit CM_INSIGHT_RUN_DIR / CM_INSIGHT_LOG_DIR
# overrides are honoured here too. Only the installation paths (lib/, build/,
# .tools/) later in this script stay tied to ROOT, which is where they live.
# ---------------------------------------------------------------------------
DATA_DIR_CFG="$(config_value "${CONFIG}" data.dir)"
REPORTS_DIR_CFG="$(config_value "${CONFIG}" reports.dir)"
LOGS_DIR_CFG="$(config_value "${CONFIG}" logs.dir)"

for spec in \
  "data:${DATA_DIR_CFG:-data}" \
  "reports:${REPORTS_DIR_CFG:-reports}" \
  "logs:${CM_INSIGHT_LOG_DIR:-${LOGS_DIR_CFG:-logs}}" \
  "secrets:${SECRETS_DIR_CFG:-conf/secrets}" \
  "run:${CM_INSIGHT_RUN_DIR:-run}"
do
  label="${spec%%:*}"
  value="${spec#*:}"
  dir="$(resolve_dir "${value}")"
  if ! mkdir -p "${dir}" 2>/dev/null; then
    err "writable: ${label} directory ${dir} cannot be created; check permissions"
    continue
  fi
  probe="${dir}/.doctor-write-test.$$"
  if ( umask 077; : > "${probe}" ) 2>/dev/null; then
    rm -f "${probe}" 2>/dev/null || true
    ok "writable: ${label} (${dir})"
  else
    rm -f "${probe}" 2>/dev/null || true
    err "writable: ${label} directory ${dir} is not writable by $(id -un 2>/dev/null || printf 'this user'); check ownership/permissions"
  fi
done

# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# 5. curl for the HTTP health checks
# ---------------------------------------------------------------------------
if command -v curl >/dev/null 2>&1; then
  ok "curl: $(command -v curl) (used by bin/start.sh and bin/status.sh for health checks)"
else
  warn "curl: not found; bin/start.sh and bin/status.sh fall back to process-existence checks only"
fi

# ---------------------------------------------------------------------------
# 6. optional library jars
# ---------------------------------------------------------------------------
LIB_TOTAL=0
for module in ibm db2 oracle app; do
  count=0
  module_dir="${ROOT}/lib/${module}"
  if [ -d "${module_dir}" ]; then
    for jar_file in "${module_dir}"/*.jar; do
      [ -e "${jar_file}" ] || continue
      count=$((count + 1))
    done
  fi
  LIB_TOTAL=$((LIB_TOTAL + count))
  if [ "${count}" -gt 0 ]; then
    ok "lib/${module}: ${count} jar(s)"
  else
    warn "lib/${module}: no jars (optional; place vendor jars here to enable that integration)"
  fi
done
if [ "${LIB_TOTAL}" -eq 0 ]; then
  warn "libraries: no jars in lib/{ibm,db2,oracle,app}; the core runtime and its tests run without them"
fi

# ---------------------------------------------------------------------------
# summary
# ---------------------------------------------------------------------------
printf -- '-----------------\n'
if [ "${FAIL_COUNT}" -gt 0 ]; then
  printf 'ERROR: doctor found %d failure(s) and %d warning(s)\n' "${FAIL_COUNT}" "${WARN_COUNT}" >&2
  exit 1
fi
if [ "${STRICT}" = true ] && [ "${WARN_COUNT}" -gt 0 ]; then
  printf 'ERROR: doctor found 0 failure(s) but %d warning(s) in --strict mode\n' "${WARN_COUNT}" >&2
  exit 1
fi
ok "doctor passed (0 failures, ${WARN_COUNT} warning(s))"
