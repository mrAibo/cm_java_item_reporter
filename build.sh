#!/usr/bin/env bash
#
# CM Insight - offline build script (JDK 17+, plain javac/jar, no network).
#
#   ./build.sh [--help]
#
# Message prefixes: "OK:" success, "WARN:" non-fatal, "ERROR:" fatal (exit != 0).
# Exit codes: 0 success, 1 build failure or the test suite's own exit code,
# 2 usage or toolchain problem.
#
# No build tool, no downloads: only the JDK tools javac, jar and java plus
# POSIX shell utilities are used.

set -euo pipefail

APP_VERSION="0.1.0-SNAPSHOT"
APP_MAIN_CLASS="com.mraibo.cminsight.app.Main"
APP_CLASS_REL="com/mraibo/cminsight/app/Main.class"
TEST_MAIN_CLASS="com.mraibo.cminsight.test.SelfTest"
TEST_CLASS_REL="com/mraibo/cminsight/test/SelfTest.class"

usage() {
  cat <<'USAGE'
Usage: ./build.sh [--help]

Offline JDK 17+ build for CM Insight. Uses only javac, jar and java.

Steps:
  1. resolve the toolchain (JAVA_HOME/bin first, else PATH); require javac, jar,
     java and a JDK major version >= 17
  2. clean and recreate build/classes and build/test-classes
  3. compile src/main/java -> build/classes   (--release 17 -encoding UTF-8 -Xlint:all)
  4. compile src/test/java -> build/test-classes (same flags, -cp build/classes)
  5. copy src/main/resources -> build/classes
  6. run com.mraibo.cminsight.test.SelfTest  (the test suite is REQUIRED)
  7. package build/cm-insight.jar (Main-Class, Implementation-Title/Version)
     and write build/.version

Optional local jars found in lib/ibm, lib/db2, lib/oracle and lib/app are added
to the compile and runtime class path when present; none of them are required.

Environment:
  JAVA_HOME   JDK 17+ home directory. Accepted as /c/tools/jdk17, C:/tools/jdk17
              or C:\tools\jdk17; when unset, javac/jar/java are taken from PATH.

Exit codes: 0 success, 1 build failure or the test suite's own exit code,
            2 usage or toolchain problem.
USAGE
}

log_ok() { printf 'OK: %s\n' "$*"; }
log_warn() { printf 'WARN: %s\n' "$*" >&2; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
fail_env() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
usage_fail() { printf 'ERROR: %s\n' "$*" >&2; usage >&2; exit 2; }

for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage; exit 0 ;;
    *) usage_fail "unknown argument: ${arg}" ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
[ -d "${SCRIPT_DIR}" ] || { printf 'ERROR: cannot locate script directory: %s\n' "${SCRIPT_DIR}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}" && pwd)" || { printf 'ERROR: cannot resolve repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

# ---------------------------------------------------------------------------
# Class path separator.
#
# Always ':' - the POSIX separator, which is natively correct on the real
# Linux/Unix target. Under Git Bash / MSYS2 the shell layer translates a
# ':'-joined list of ABSOLUTE POSIX paths into the Windows form the native JVM
# expects, whereas a ';'-joined list is passed through untranslated and javac
# then fails with "bad path element". Do NOT "fix" this back to ';': every class
# path entry below is an absolute path derived from the repository root, which is
# what keeps that translation reliable on Windows and correct on Linux/macOS.
# ---------------------------------------------------------------------------
CP_SEP=":"

# ---------------------------------------------------------------------------
# Toolchain discovery
# ---------------------------------------------------------------------------
JH="${JAVA_HOME:-}"
if [ -n "${JH}" ]; then
  # Accept a Windows-style JAVA_HOME (C:\jdk17 or C:/jdk17) inside an MSYS shell.
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
  JAVAC="${JH}/bin/javac"
  JAR="${JH}/bin/jar"
  JAVA="${JH}/bin/java"
  TOOLCHAIN_SOURCE="JAVA_HOME=${JH}"
else
  JAVAC="$(command -v javac 2>/dev/null || true)"
  JAR="$(command -v jar 2>/dev/null || true)"
  JAVA="$(command -v java 2>/dev/null || true)"
  TOOLCHAIN_SOURCE="PATH"
fi

if [ -z "${JAVAC}" ] || [ ! -x "${JAVAC}" ]; then
  fail_env "javac not found${JAVAC:+ at ${JAVAC}} (toolchain source: ${TOOLCHAIN_SOURCE}). Install a JDK 17+ (e.g. Temurin 17) and either set JAVA_HOME to the JDK home directory or put its bin/ directory on PATH. No network is used by this script, so the JDK must be installed locally."
fi
if [ -z "${JAR}" ] || [ ! -x "${JAR}" ]; then
  fail_env "jar not found${JAR:+ at ${JAR}} (toolchain source: ${TOOLCHAIN_SOURCE}). The build needs the JDK 'jar' tool; install a full JDK 17+ (a JRE-only installation is not enough)."
fi
if [ -z "${JAVA}" ] || [ ! -x "${JAVA}" ]; then
  fail_env "java not found${JAVA:+ at ${JAVA}} (toolchain source: ${TOOLCHAIN_SOURCE}). Install a JDK 17+ and/or fix JAVA_HOME."
fi

tool_version_string() { "$1" -version 2>&1 || true; }

tool_major() {
  # Prints the major version digits reported by "$1" -version, or nothing.
  _out="$(tool_version_string "$1")"
  _first="${_out%%$'\n'*}"
  _num="${_first#* }"
  _num="${_num%%.*}"
  printf '%s' "${_num}" | tr -cd '0-9'
}

JAVAC_VERSION_LINE="$(tool_version_string "${JAVAC}")"
JAVAC_VERSION_LINE="${JAVAC_VERSION_LINE%%$'\n'*}"
JAVAC_MAJOR="$(tool_major "${JAVAC}")"
case "${JAVAC_MAJOR}" in
  ''|*[!0-9]*)
    fail_env "cannot parse the javac version from '${JAVAC} -version' (output: ${JAVAC_VERSION_LINE:-<empty>}). Install a JDK 17+ (Temurin 17 recommended) and re-run."
    ;;
esac
if [ "${JAVAC_MAJOR}" -lt 17 ]; then
  fail_env "JDK 17+ is required but javac reports '${JAVAC_VERSION_LINE}'. Install a JDK 17+ (e.g. Temurin 17) and set JAVA_HOME to it, or put its bin/ first on PATH."
fi

JAVA_VERSION_LINE="$(tool_version_string "${JAVA}")"
JAVA_VERSION_LINE="${JAVA_VERSION_LINE%%$'\n'*}"
JAVA_MAJOR="$(tool_major "${JAVA}")"
case "${JAVA_MAJOR}" in
  ''|*[!0-9]*)
    log_warn "cannot parse the java version from '${JAVA} -version' (output: ${JAVA_VERSION_LINE:-<empty>}); continuing with javac ${JAVAC_VERSION_LINE}"
    ;;
  *)
    if [ "${JAVA_MAJOR}" -lt 17 ]; then
      fail_env "javac is JDK ${JAVAC_MAJOR} but java reports '${JAVA_VERSION_LINE}'. Classes compiled with --release 17 cannot run there; point JAVA_HOME (or PATH) at a JDK 17+ for both tools."
    fi
    ;;
esac

log_ok "toolchain (${TOOLCHAIN_SOURCE}): ${JAVAC_VERSION_LINE}; java ${JAVA_MAJOR}; class path separator '${CP_SEP}'"

# ---------------------------------------------------------------------------
# Layout
# ---------------------------------------------------------------------------
BUILD_DIR="${ROOT}/build"
CLASSES_DIR="${BUILD_DIR}/classes"
TEST_CLASSES_DIR="${BUILD_DIR}/test-classes"
JAR_FILE="${BUILD_DIR}/cm-insight.jar"
MAIN_SRC_DIR="${ROOT}/src/main/java"
TEST_SRC_DIR="${ROOT}/src/test/java"
RESOURCES_DIR="${ROOT}/src/main/resources"

[ -d "${MAIN_SRC_DIR}" ] || fail_env "missing source directory ${MAIN_SRC_DIR}; run this script from the CM Insight working tree."
[ -d "${ROOT}/src" ] || fail_env "missing ${ROOT}/src; this does not look like the CM Insight working tree."
if [ -e "${BUILD_DIR}" ] && [ ! -d "${BUILD_DIR}" ]; then
  fail_env "${BUILD_DIR} exists and is not a directory; remove it and re-run."
fi

# ---------------------------------------------------------------------------
# Optional local jars (absent in a clean checkout - and that is fine)
# ---------------------------------------------------------------------------
LIB_CP=""
LIB_COUNT=0
for module in ibm db2 oracle app; do
  dir="${ROOT}/lib/${module}"
  [ -d "${dir}" ] || continue
  for jar_file in "${dir}"/*.jar; do
    [ -e "${jar_file}" ] || continue
    LIB_COUNT=$((LIB_COUNT + 1))
    if [ -z "${LIB_CP}" ]; then LIB_CP="${jar_file}"; else LIB_CP="${LIB_CP}${CP_SEP}${jar_file}"; fi
  done
done
if [ "${LIB_COUNT}" -gt 0 ]; then
  log_ok "optional local jars on the class path: ${LIB_COUNT} from lib/{ibm,db2,oracle,app}"
else
  log_warn "no optional jars in lib/{ibm,db2,oracle,app}; building the core runtime only (this is expected for the offline bootstrap build)"
fi

# ---------------------------------------------------------------------------
# 2. clean
# ---------------------------------------------------------------------------
rm -rf "${CLASSES_DIR}" "${TEST_CLASSES_DIR}"
rm -f "${JAR_FILE}" "${BUILD_DIR}/.version" "${BUILD_DIR}/manifest.mf"
mkdir -p "${CLASSES_DIR}" "${TEST_CLASSES_DIR}"
log_ok "cleaned ${CLASSES_DIR} and ${TEST_CLASSES_DIR}"

# ---------------------------------------------------------------------------
# 3. main sources
# ---------------------------------------------------------------------------
MAIN_SOURCES=()
while IFS= read -r -d '' file; do MAIN_SOURCES+=("${file}"); done < <(find "${MAIN_SRC_DIR}" -type f -name '*.java' -print0 2>/dev/null)
if [ "${#MAIN_SOURCES[@]}" -eq 0 ]; then
  fail "no Java sources found under ${MAIN_SRC_DIR}; nothing to build."
fi

JAVAC_ARGS=(--release 17 -encoding UTF-8 -Xlint:all -d "${CLASSES_DIR}")
if [ -n "${LIB_CP}" ]; then JAVAC_ARGS+=(-cp "${LIB_CP}"); fi
log_ok "compiling ${#MAIN_SOURCES[@]} main source file(s)"
"${JAVAC}" "${JAVAC_ARGS[@]}" "${MAIN_SOURCES[@]}"
[ -f "${CLASSES_DIR}/${APP_CLASS_REL}" ] || fail "compilation finished but ${APP_MAIN_CLASS} is missing from ${CLASSES_DIR}; the packaged jar would not be runnable."
log_ok "main classes in ${CLASSES_DIR}"

# ---------------------------------------------------------------------------
# 4. test sources - REQUIRED
# ---------------------------------------------------------------------------
if [ ! -d "${TEST_SRC_DIR}" ]; then
  fail "test sources are required but ${TEST_SRC_DIR} does not exist. Add the dependency-free test suite (entry point ${TEST_MAIN_CLASS}) - the build never silently skips tests."
fi
TEST_SOURCES=()
while IFS= read -r -d '' file; do TEST_SOURCES+=("${file}"); done < <(find "${TEST_SRC_DIR}" -type f -name '*.java' -print0 2>/dev/null)
if [ "${#TEST_SOURCES[@]}" -eq 0 ]; then
  fail "test sources are required but ${TEST_SRC_DIR} contains no .java files. Add the dependency-free test suite (entry point ${TEST_MAIN_CLASS}) - the build never silently skips tests."
fi

TEST_JAVAC_ARGS=(--release 17 -encoding UTF-8 -Xlint:all -d "${TEST_CLASSES_DIR}")
TEST_COMPILE_CP="${CLASSES_DIR}"
if [ -n "${LIB_CP}" ]; then TEST_COMPILE_CP="${CLASSES_DIR}${CP_SEP}${LIB_CP}"; fi
TEST_JAVAC_ARGS+=(-cp "${TEST_COMPILE_CP}")
log_ok "compiling ${#TEST_SOURCES[@]} test source file(s)"
"${JAVAC}" "${TEST_JAVAC_ARGS[@]}" "${TEST_SOURCES[@]}"
log_ok "test classes in ${TEST_CLASSES_DIR}"

# ---------------------------------------------------------------------------
# 5. resources
# ---------------------------------------------------------------------------
if [ -d "${RESOURCES_DIR}" ]; then
  cp -R "${RESOURCES_DIR}/." "${CLASSES_DIR}/"
  log_ok "copied src/main/resources into ${CLASSES_DIR}"
else
  log_warn "no ${RESOURCES_DIR}; the jar will contain classes only"
fi

# ---------------------------------------------------------------------------
# 6. run the test suite (required, must fail loudly when missing)
# ---------------------------------------------------------------------------
TEST_RUN_CP="${TEST_CLASSES_DIR}${CP_SEP}${CLASSES_DIR}"
if [ -n "${LIB_CP}" ]; then TEST_RUN_CP="${TEST_RUN_CP}${CP_SEP}${LIB_CP}"; fi

if [ ! -f "${TEST_CLASSES_DIR}/${TEST_CLASS_REL}" ]; then
  fail "${TEST_CLASS_REL} was not produced from src/test/java; the required test entry point ${TEST_MAIN_CLASS} is missing. Add it (or fix the package) - the build must not pass without the test suite."
fi

log_ok "running the test suite: ${TEST_MAIN_CLASS}"
set +e
"${JAVA}" -cp "${TEST_RUN_CP}" "${TEST_MAIN_CLASS}"
TEST_RC=$?
set -e
if [ "${TEST_RC}" -ne 0 ]; then
  printf 'ERROR: the test suite %s failed with exit code %s; fix the failing tests before packaging.\n' "${TEST_MAIN_CLASS}" "${TEST_RC}" >&2
  exit "${TEST_RC}"
fi
log_ok "test suite passed"

# ---------------------------------------------------------------------------
# 7. package
# ---------------------------------------------------------------------------
MANIFEST_FILE="${BUILD_DIR}/manifest.mf"
cat > "${MANIFEST_FILE}" <<MANIFEST
Manifest-Version: 1.0
Main-Class: ${APP_MAIN_CLASS}
Implementation-Title: CM Insight
Implementation-Version: ${APP_VERSION}

MANIFEST
"${JAR}" cfm "${JAR_FILE}" "${MANIFEST_FILE}" -C "${CLASSES_DIR}" .
[ -s "${JAR_FILE}" ] || fail "the jar was not created correctly: ${JAR_FILE} is missing or empty."
printf '%s\n' "${APP_VERSION}" > "${BUILD_DIR}/.version"
log_ok "wrote ${BUILD_DIR}/.version (${APP_VERSION})"
log_ok "built ${JAR_FILE}"
log_ok "run it with: ./bin/cm-insight --config conf/application.properties   (or ./bin/start.sh)"
