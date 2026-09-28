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
  - inline-password warning and non-loopback exposure guard
  - required writable directories (data, reports, logs, secrets, run)
  - repository profiles: readability and secret resolvability
    (environment variable names are reported, NEVER their values)
  - curl availability for the HTTP health checks
  - optional library jars in lib/{ibm,db2,oracle,app}

Options:
  --strict   treat warnings as failures (exit 1 when any warning is present)

Environment:
  JAVA_HOME           JDK 17+ home (may be /c/tools/jdk17, C:/tools/jdk17, C:\tools\jdk17)
  CM_INSIGHT_CONFIG   alternative configuration file (default conf/application.properties)

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

FAIL_COUNT=0
WARN_COUNT=0
ok()   { printf 'OK:    %s\n' "$*"; }
warn() { printf 'WARN:  %s\n' "$*" >&2; WARN_COUNT=$((WARN_COUNT + 1)); }
err()  { printf 'ERROR: %s\n' "$*" >&2; FAIL_COUNT=$((FAIL_COUNT + 1)); }

printf 'CM Insight doctor\n'
printf '=================\n'
printf 'root:   %s\n' "${ROOT}"
printf 'config: %s\n' "${CONFIG}"
if [ "${STRICT}" = true ]; then printf 'mode:   strict (warnings are failures)\n'; fi

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
is_loopback_bind() {
  case "$1" in
    127.*|localhost|::1|\[::1\]) return 0 ;;
    *) return 1 ;;
  esac
}

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

env_is_set() {
  # $1 = environment variable name; true when set and non-empty (value never printed)
  _name="$1"
  case "${_name}" in ''|*[!A-Za-z0-9_]*) return 1 ;; esac
  if command -v printenv >/dev/null 2>&1; then
    _val="$(printenv "${_name}" 2>/dev/null || true)"
  else
    _val="${!_name:-}"
  fi
  [ -n "${_val}" ]
}

resolve_dir() {
  # $1 = configured directory value
  case "$1" in
    '' ) printf '' ;;
    /*) printf '%s' "$1" ;;
    [A-Za-z]:[\\/]*) printf '%s' "$1" ;;
    *) printf '%s/%s' "${ROOT}" "$1" ;;
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
# 2. configuration
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

INLINE_PW="$(config_value "${CONFIG}" web.auth.password)"
if [ -n "${INLINE_PW}" ]; then
  warn "config: an inline web.auth.password is set; prefer web.auth.password.env (or web.auth.password.file under secrets.dir). The value is never printed by this script."
fi

for key in profiles.dir classifications.file data.dir reports.dir logs.dir secrets.dir; do
  raw="$(config_value "${CONFIG}" "${key}")"
  case "${raw}" in
    *\\*)
      err "config: ${key}=${raw} contains a backslash; a single backslash is an escape character in .properties files, so the application reads a munged path. Use forward slashes (${key}=conf/profiles) or doubled backslashes."
      ;;
  esac
done

WEB_PW_ENV="$(config_value "${CONFIG}" web.auth.password.env)"
WEB_PW_FILE="$(config_value "${CONFIG}" web.auth.password.file)"
WEB_PW_RESOLVABLE=false
if [ -n "${WEB_PW_ENV}" ]; then
  if env_is_set "${WEB_PW_ENV}"; then
    ok "web password: environment variable ${WEB_PW_ENV} is set (value not shown)"
    WEB_PW_RESOLVABLE=true
  else
    warn "web password: environment variable ${WEB_PW_ENV} is referenced but not set (value not shown); the loopback admin/admin fallback applies"
  fi
fi
if [ -n "${WEB_PW_FILE}" ]; then
  case "${WEB_PW_FILE}" in
    /*|[A-Za-z]:[\\/]*) WEB_PW_PATH="${WEB_PW_FILE}" ;;
    *) WEB_PW_PATH="${SECRETS_DIR}/${WEB_PW_FILE}" ;;
  esac
  if [ -r "${WEB_PW_PATH}" ]; then
    ok "web password: secret file ${WEB_PW_PATH} is readable (contents not shown)"
    WEB_PW_RESOLVABLE=true
  else
    warn "web password: secret file ${WEB_PW_PATH} is missing or unreadable (contents are never shown)"
  fi
fi
if [ -z "${WEB_PW_ENV}" ] && [ -z "${WEB_PW_FILE}" ] && [ -z "${INLINE_PW}" ]; then
  if is_loopback_bind "${BIND}"; then
    warn "web password: none configured; on loopback (${BIND}) the application starts with the admin/admin fallback and warns. Configure web.auth.password.env for anything real."
  else
    err "web password: none configured while web.bind=${BIND} is not loopback; the application refuses to start fail-closed. Set web.auth.password.env (name only is ever printed) or bind to 127.0.0.1."
  fi
fi

if [ -n "${INLINE_PW}" ] || [ -n "${WEB_PW_ENV}" ] || [ -n "${WEB_PW_FILE}" ]; then
  if is_loopback_bind "${BIND}"; then
    ok "bind: ${BIND} (loopback only) port ${PORT}"
  else
    if [ "${WEB_PW_RESOLVABLE}" = true ]; then
      ok "bind: ${BIND} (non-loopback) with a resolvable web password; port ${PORT}"
    else
      err "bind: ${BIND} is not loopback but no web password resolves; the application refuses to start. Set the referenced environment variable or bind to 127.0.0.1."
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 3. required writable directories
# ---------------------------------------------------------------------------
DATA_DIR_CFG="$(config_value "${CONFIG}" data.dir)"
REPORTS_DIR_CFG="$(config_value "${CONFIG}" reports.dir)"
LOGS_DIR_CFG="$(config_value "${CONFIG}" logs.dir)"

for spec in \
  "data:${DATA_DIR_CFG:-data}" \
  "reports:${REPORTS_DIR_CFG:-reports}" \
  "logs:${LOGS_DIR_CFG:-logs}" \
  "secrets:${SECRETS_DIR_CFG:-conf/secrets}" \
  "run:run"
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
# 4. repository profiles and secret resolvability (names only)
# ---------------------------------------------------------------------------
PROFILES_DIR_CFG="$(config_value "${CONFIG}" profiles.dir)"
PROFILES_DIR="$(resolve_dir "${PROFILES_DIR_CFG:-conf/profiles}")"
if [ -d "${PROFILES_DIR}" ]; then
  PROFILE_COUNT=0
  for profile in "${PROFILES_DIR}"/*.properties; do
    [ -f "${profile}" ] || continue
    PROFILE_COUNT=$((PROFILE_COUNT + 1))
    if [ ! -r "${profile}" ]; then
      warn "profile: ${profile} is not readable"
      continue
    fi
    ok "profile: ${profile}"

    while IFS='|' read -r key name; do
      [ -n "${key}" ] || continue
      case "${key}" in
        *.password.env)
          if env_is_set "${name}"; then
            ok "  secret: ${key} -> ${name} is set (value not shown)"
          else
            warn "  secret: ${key} -> ${name} is NOT set (value never shown); this repository cannot connect until it is exported"
          fi
          ;;
        *.user.env)
          if env_is_set "${name}"; then
            ok "  secret: ${key} -> ${name} is set"
          else
            warn "  secret: ${key} -> ${name} is NOT set"
          fi
          ;;
        *.password.file|*.user.file)
          case "${name}" in
            /*|[A-Za-z]:[\\/]*) secret_path="${name}" ;;
            *) secret_path="${SECRETS_DIR}/${name}" ;;
          esac
          if [ -r "${secret_path}" ]; then
            ok "  secret: ${key} -> ${secret_path} is readable (contents not shown)"
          else
            warn "  secret: ${key} -> ${secret_path} is missing or unreadable (contents are never shown)"
          fi
          ;;
      esac
    done < <(sed -n 's/^[[:space:]]*\([A-Za-z0-9._-]*\.\(password\|user\)\.\(env\|file\)\)[[:space:]]*=[[:space:]]*\(.*\)$/\1|\4/p' "${profile}" 2>/dev/null | tr -d '\r')
  done
  if [ "${PROFILE_COUNT}" -eq 0 ]; then
    warn "profile: no repository profiles in ${PROFILES_DIR} (expected *.properties); add one before connecting to a repository"
  fi
else
  warn "profile: ${PROFILES_DIR} does not exist; no repository profiles are configured yet"
fi

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
