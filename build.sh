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
Usage: ./build.sh [--require-ibm] [--check-ibm-isolation] [--help]

Offline JDK 17+ build for CM Insight. Uses only javac, jar and java.

Steps:
  1. resolve the toolchain (JAVA_HOME/bin first, else PATH); require javac, jar,
     java and a JDK major version >= 17
  2. enforce the source guards: no com.ibm reference under src/main/java, and no
     IBM CM mutating call under src/ibm/java
  3. clean and recreate the build output directories
  4. compile src/main/java -> build/classes   (--release 17 -encoding UTF-8 -Xlint:all)
     The core class path NEVER contains an IBM JAR or an IBM stub, so a core
     source that needs an SDK type cannot compile - the isolation is structural,
     not a convention.
  5. compile src/test/java -> build/test-classes (same flags, -cp build/classes)
  6. resolve the IBM SDK: lib/ibm/*.jar when present, otherwise the test-only
     compile stubs in tests/ibm-stubs (signature-only, never packaged)
  7. compile src/ibm/java -> build/ibm-classes against the SDK set
  8. compile src/ibm-test/java -> build/ibm-test-classes when present
  9. copy src/main/resources -> build/classes and src/ibm/resources -> build/ibm-classes
 10. run com.mraibo.cminsight.test.SelfTest (the test suite is REQUIRED), then the
     IBM suites when the IBM source set was compiled
 11. package build/cm-insight.jar (Main-Class, Implementation-Title/Version)
     from build/classes plus build/ibm-classes, and write build/.version

Optional local jars found in lib/ibm, lib/db2, lib/oracle and lib/app are added
to the compile and runtime class path when present; none of them are required.

Options:
  --require-ibm          fail unless a real IBM CM SDK is present in lib/ibm.
                         Use this when the operator requires IBM support: a build
                         that silently compiles against signature stubs and ships
                         is exactly the outcome this flag exists to prevent.
  --check-ibm-isolation  run only the source guards and exit. No JDK toolchain is
                         needed. Exit 0 when both hold, 2 when one is violated.

Environment:
  JAVA_HOME   JDK 17+ home directory. Accepted as /c/tools/jdk17, C:/tools/jdk17
              or C:\tools\jdk17; when unset, javac/jar/java are taken from PATH.

Exit codes: 0 success, 1 build failure or the test suite's own exit code,
            2 usage, toolchain problem or a violated source guard.
USAGE
}

log_ok() { printf 'OK: %s\n' "$*"; }
log_warn() { printf 'WARN: %s\n' "$*" >&2; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
fail_env() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
usage_fail() { printf 'ERROR: %s\n' "$*" >&2; usage >&2; exit 2; }

REQUIRE_IBM=false
CHECK_ISOLATION_ONLY=false
for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage; exit 0 ;;
    --require-ibm) REQUIRE_IBM=true ;;
    --check-ibm-isolation) CHECK_ISOLATION_ONLY=true ;;
    *) usage_fail "unknown argument: ${arg}" ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
[ -d "${SCRIPT_DIR}" ] || { printf 'ERROR: cannot locate script directory: %s\n' "${SCRIPT_DIR}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}" && pwd)" || { printf 'ERROR: cannot resolve repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

# ---------------------------------------------------------------------------
# Source guards (Goal 02 sections A and H).
#
# This runs BEFORE the toolchain is resolved, so `--check-ibm-isolation` works in
# an environment with no JDK at all and the guard is available to a test that only
# wants to prove the rule. One implementation, in tests/shell/ibm_guard.sh, is
# shared with the committed shell suite so the build step and the test can never
# disagree about what is forbidden.
# ---------------------------------------------------------------------------
IBM_GUARD="${ROOT}/tests/shell/ibm_guard.sh"
if [ "${CHECK_ISOLATION_ONLY}" = true ]; then
  [ -f "${IBM_GUARD}" ] || { printf 'ERROR: %s is missing; the source guards cannot be checked.\n' "${IBM_GUARD}" >&2; exit 2; }
  bash "${IBM_GUARD}"
  exit $?
fi
if [ -f "${IBM_GUARD}" ]; then
  bash "${IBM_GUARD}" || { printf 'ERROR: the Goal 02 source guards refused this tree; see the messages above.\n' >&2; exit 2; }
else
  # A missing guard is a missing rule, not a pass. Failing here is deliberate: the
  # read-only guarantee is a product property, so a build that cannot check it must
  # not claim to have checked it.
  printf 'ERROR: %s is missing; the read-only and SDK-isolation guards cannot run.\n' "${IBM_GUARD}" >&2
  exit 2
fi


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
# The optional-source-set outputs deliberately live OUTSIDE build/. A concurrent build in the same tree
# runs `rm -rf build/classes build/test-classes`, and sharing one directory meant a second build could
# delete the IBM stub classes between this build's javac and its signature check - which then reported a
# signature mismatch for stubs that were perfectly correct. That happened repeatedly while several
# members built at once and cost real diagnosis time, so the fix is structural rather than a re-run:
# nothing another build deletes lives under the path these artifacts use.
OUT_DIR="${ROOT}/target"
IBM_CLASSES_DIR="${OUT_DIR}/ibm-classes"
IBM_TEST_CLASSES_DIR="${OUT_DIR}/ibm-test-classes"
IBM_STUB_CLASSES_DIR="${OUT_DIR}/ibm-stub-classes"
MAIN_SRC_DIR="${ROOT}/src/main/java"
TEST_SRC_DIR="${ROOT}/src/test/java"
RESOURCES_DIR="${ROOT}/src/main/resources"
IBM_SRC_DIR="${ROOT}/src/ibm/java"
IBM_TEST_SRC_DIR="${ROOT}/src/ibm-test/java"
IBM_RESOURCES_DIR="${ROOT}/src/ibm/resources"
IBM_STUB_DIR="${ROOT}/tests/ibm-stubs"

[ -d "${MAIN_SRC_DIR}" ] || fail_env "missing source directory ${MAIN_SRC_DIR}; run this script from the CM Insight working tree."
[ -d "${ROOT}/src" ] || fail_env "missing ${ROOT}/src; this does not look like the CM Insight working tree."
if [ -e "${BUILD_DIR}" ] && [ ! -d "${BUILD_DIR}" ]; then
  fail_env "${BUILD_DIR} exists and is not a directory; remove it and re-run."
fi

# ---------------------------------------------------------------------------
# Optional local jars.
#
# lib/ibm is NOT part of the core class path. The whole point of the optional
# source set is that src/main/java compiles with no IBM type reachable at all, so
# a core file that needs one fails here instead of drifting into the adapter. The
# IBM jars are resolved separately, below, and only ever reach src/ibm/java.
# ---------------------------------------------------------------------------
LIB_CP=""
LIB_COUNT=0
for module in db2 oracle app; do
  dir="${ROOT}/lib/${module}"
  [ -d "${dir}" ] || continue
  for jar_file in "${dir}"/*.jar; do
    [ -e "${jar_file}" ] || continue
    LIB_COUNT=$((LIB_COUNT + 1))
    if [ -z "${LIB_CP}" ]; then LIB_CP="${jar_file}"; else LIB_CP="${LIB_CP}${CP_SEP}${jar_file}"; fi
  done
done
if [ "${LIB_COUNT}" -gt 0 ]; then
  log_ok "optional local jars on the core class path: ${LIB_COUNT} from lib/{db2,oracle,app}"
else
  log_ok "core class path carries no optional jars (expected for the offline bootstrap build)"
fi

# ---------------------------------------------------------------------------
# IBM SDK resolution for the optional source set.
#
# A real SDK in lib/ibm wins, because it is the only thing that can validate the
# adapter against reality. The test-only stubs are the fallback that keeps CI able
# to compile src/ibm/java with zero proprietary JARs - they are signatures only,
# and they are never added to the core class path or packaged.
# ---------------------------------------------------------------------------
IBM_CP=""
IBM_CP_KIND=""
IBM_JAR_COUNT=0
for jar_file in "${ROOT}"/lib/ibm/*.jar; do
  [ -e "${jar_file}" ] || continue
  IBM_JAR_COUNT=$((IBM_JAR_COUNT + 1))
  if [ -z "${IBM_CP}" ]; then IBM_CP="${jar_file}"; else IBM_CP="${IBM_CP}${CP_SEP}${jar_file}"; fi
done

if [ "${IBM_JAR_COUNT}" -gt 0 ]; then
  IBM_CP_KIND="real"
  log_ok "IBM SDK: ${IBM_JAR_COUNT} jar(s) from lib/ibm - compiling the optional source set against the REAL SDK"
elif [ -d "${IBM_STUB_DIR}" ]; then
  IBM_CP_KIND="stubs"
  log_warn "IBM SDK: none in lib/ibm; compiling the optional source set against the TEST-ONLY signature stubs in tests/ibm-stubs. This is a compile check, NOT SDK validation."
else
  IBM_CP_KIND="none"
fi

# The stubs are Java SOURCES, so they are useless on a class path until they are
# compiled. Compiling them into their own directory does three things at once:
# it makes the stub set a real class path entry, it fails on a stub that does not
# itself compile (which would otherwise look like the adapter being broken), and
# it keeps the stubs out of build/classes and out of the packaged jar by
# construction rather than by a copy step somebody has to remember.
#
# IBM_STUB_CLASSES_DIR is defined with the layout above, under target/ rather than
# build/, for the concurrency reason documented there.
STUB_SOURCES=()
if [ -d "${IBM_STUB_DIR}" ]; then
  while IFS= read -r -d '' file; do STUB_SOURCES+=("${file}"); done \
    < <(find "${IBM_STUB_DIR}" -type f -name '*.java' -print0 2>/dev/null)
fi

if [ "${REQUIRE_IBM}" = true ] && [ "${IBM_CP_KIND}" != "real" ]; then
  fail "IBM support was required (--require-ibm) but no IBM SDK jar was found in ${ROOT}/lib/ibm. Vendor JARs are never downloaded or committed, so place the IBM CM 8.7 SDK jars there (see lib/README.md) or drop --require-ibm to build the core runtime only."
fi

# ---------------------------------------------------------------------------
# 2. clean
# ---------------------------------------------------------------------------
rm -rf "${CLASSES_DIR}" "${TEST_CLASSES_DIR}" "${IBM_CLASSES_DIR}" "${IBM_TEST_CLASSES_DIR}" "${IBM_STUB_CLASSES_DIR}"
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

# The core tests see build/classes and the non-IBM optional jars ONLY. The IBM
# jars and the stubs are deliberately absent: a Goal 01 suite that started using
# an SDK type would stop compiling here, which is the same isolation rule the
# source guard enforces, checked a second time by the compiler.
TEST_JAVAC_ARGS=(--release 17 -encoding UTF-8 -Xlint:all -d "${TEST_CLASSES_DIR}")
TEST_COMPILE_CP="${CLASSES_DIR}"
if [ -n "${LIB_CP}" ]; then TEST_COMPILE_CP="${CLASSES_DIR}${CP_SEP}${LIB_CP}"; fi
TEST_JAVAC_ARGS+=(-cp "${TEST_COMPILE_CP}")
log_ok "compiling ${#TEST_SOURCES[@]} test source file(s)"
"${JAVAC}" "${TEST_JAVAC_ARGS[@]}" "${TEST_SOURCES[@]}"
log_ok "test classes in ${TEST_CLASSES_DIR}"

# ---------------------------------------------------------------------------
# 4b. optional IBM source set
#
# Compiled AFTER the core (so it can use the core) and against the SDK set only
# (so it cannot be reached by mistake). A missing src/ibm/java is a legitimate
# core-only checkout and is reported, never treated as an error.
# ---------------------------------------------------------------------------
IBM_MAIN_SOURCES=()
if [ -d "${IBM_SRC_DIR}" ]; then
  while IFS= read -r -d '' file; do IBM_MAIN_SOURCES+=("${file}"); done \
    < <(find "${IBM_SRC_DIR}" -type f -name '*.java' -print0 2>/dev/null)
fi

if [ "${#IBM_MAIN_SOURCES[@]}" -gt 0 ]; then
  if [ "${IBM_CP_KIND}" = "none" ]; then
    fail "src/ibm/java exists but there is no IBM SDK to compile it against: lib/ibm has no jar and ${IBM_STUB_DIR} is missing. Place the IBM CM 8.7 SDK jars in lib/ibm (see lib/README.md), or remove src/ibm/java for a core-only build."
  fi

  if [ "${IBM_CP_KIND}" = "stubs" ]; then
    if [ "${#STUB_SOURCES[@]}" -eq 0 ]; then
      fail "${IBM_STUB_DIR} contains no .java file, so src/ibm/java has nothing to compile against. Restore the test-only stubs (see tests/ibm-stubs/README.md) or place the real SDK jars in lib/ibm."
    fi
    rm -rf "${IBM_STUB_CLASSES_DIR}"
    mkdir -p "${IBM_STUB_CLASSES_DIR}"
    log_ok "compiling ${#STUB_SOURCES[@]} IBM stub source file(s) into ${IBM_STUB_CLASSES_DIR} (test-only; never packaged)"
    "${JAVAC}" --release 17 -encoding UTF-8 -Xlint:all -d "${IBM_STUB_CLASSES_DIR}" "${STUB_SOURCES[@]}"

    # Pin the stubs to their committed expectation. This is what stops a stub being
    # quietly widened or renamed into an API that does not exist: the failure would
    # otherwise surface only on a machine that owns the proprietary JAR.
    if [ -x "${IBM_STUB_DIR}/check-signatures.sh" ]; then
      log_ok "checking the stub signatures against tests/ibm-stubs/EXPECTED_SIGNATURES.txt"
      # Distinguish the two failures, because they have completely different causes and
      # the checker's own message names only the second one. An empty classes directory
      # means the javac above produced nothing - typically because a CONCURRENT build in
      # the same tree wiped build/ between the compile and this check - and reporting
      # that as "the stubs no longer match" sends the reader to edit a file that is
      # perfectly correct. Measured: exactly that happened while two members built at
      # once, and the misleading line cost a diagnosis.
      if [ -z "$(find "${IBM_STUB_CLASSES_DIR}" -name '*.class' -print -quit 2>/dev/null)" ]; then
        fail "the stub compile above produced no class files under ${IBM_STUB_CLASSES_DIR}, so the signature check has nothing to inspect. Nothing is wrong with tests/ibm-stubs: this usually means another build in the same tree deleted build/ while this one was running. Re-run ./build.sh when no other build is active."
      fi
      if ! bash "${IBM_STUB_DIR}/check-signatures.sh" --classes "${IBM_STUB_CLASSES_DIR}"; then
        fail "the IBM compile stubs no longer match tests/ibm-stubs/EXPECTED_SIGNATURES.txt. The stubs must mirror the real IBM CM 8.7 SDK; if the change was deliberate, re-run tests/ibm-stubs/check-signatures.sh --write and commit the result."
      fi
    else
      fail "${IBM_STUB_DIR}/check-signatures.sh is missing or not executable; the stubs cannot be pinned to the committed expectation and a drifted stub would go unnoticed."
    fi
    IBM_CP="${IBM_STUB_CLASSES_DIR}"
  fi

  mkdir -p "${IBM_CLASSES_DIR}"
  IBM_JAVAC_ARGS=(--release 17 -encoding UTF-8 -Xlint:all -d "${IBM_CLASSES_DIR}")
  IBM_JAVAC_ARGS+=(-cp "${CLASSES_DIR}${CP_SEP}${IBM_CP}")
  log_ok "compiling ${#IBM_MAIN_SOURCES[@]} IBM source file(s) against the ${IBM_CP_KIND} SDK"
  "${JAVAC}" "${IBM_JAVAC_ARGS[@]}" "${IBM_MAIN_SOURCES[@]}"
  log_ok "IBM adapter classes in ${IBM_CLASSES_DIR}"

  # A stub compile is a syntax-and-signature check, never SDK validation. Saying
  # so on every build is the cheapest way to stop the two being confused later,
  # in a STATUS.md entry or a release note.
  if [ "${IBM_CP_KIND}" = "stubs" ]; then
    log_warn "the IBM adapter was compiled against SIGNATURE STUBS, not the real SDK; this proves it compiles, not that it matches IBM's runtime"
  fi
else
  log_ok "no src/ibm/java source set present; building the core runtime only"
fi

# ---------------------------------------------------------------------------
# 4c. optional IBM tests
# ---------------------------------------------------------------------------
IBM_TEST_SOURCES=()
if [ -d "${IBM_TEST_SRC_DIR}" ]; then
  while IFS= read -r -d '' file; do IBM_TEST_SOURCES+=("${file}"); done \
    < <(find "${IBM_TEST_SRC_DIR}" -type f -name '*.java' -print0 2>/dev/null)
fi
if [ "${#IBM_TEST_SOURCES[@]}" -gt 0 ]; then
  [ "${#IBM_MAIN_SOURCES[@]}" -gt 0 ] || fail "${IBM_TEST_SRC_DIR} has ${#IBM_TEST_SOURCES[@]} source file(s) but src/ibm/java is empty; the IBM tests have nothing to test."
  mkdir -p "${IBM_TEST_CLASSES_DIR}"
  IBM_TEST_JAVAC_ARGS=(--release 17 -encoding UTF-8 -Xlint:all -d "${IBM_TEST_CLASSES_DIR}")
  IBM_TEST_CP="${TEST_CLASSES_DIR}${CP_SEP}${CLASSES_DIR}${CP_SEP}${IBM_CLASSES_DIR}${CP_SEP}${IBM_CP}"
  if [ -n "${LIB_CP}" ]; then IBM_TEST_CP="${IBM_TEST_CP}${CP_SEP}${LIB_CP}"; fi
  IBM_TEST_JAVAC_ARGS+=(-cp "${IBM_TEST_CP}")
  log_ok "compiling ${#IBM_TEST_SOURCES[@]} IBM test source file(s)"
  "${JAVAC}" "${IBM_TEST_JAVAC_ARGS[@]}" "${IBM_TEST_SOURCES[@]}"
  log_ok "IBM test classes in ${IBM_TEST_CLASSES_DIR}"
fi

# ---------------------------------------------------------------------------
# 5. resources
# ---------------------------------------------------------------------------
if [ -d "${RESOURCES_DIR}" ]; then
  cp -R "${RESOURCES_DIR}/." "${CLASSES_DIR}/"
  log_ok "copied src/main/resources into ${CLASSES_DIR}"
else
  log_warn "no ${RESOURCES_DIR}; the jar will contain classes only"
fi

# The IBM source set's resources carry the ServiceLoader registration, so they are
# merged into the same jar without joining the core class path at compile time.
if [ -d "${IBM_RESOURCES_DIR}" ] && [ -d "${IBM_CLASSES_DIR}" ]; then
  cp -R "${IBM_RESOURCES_DIR}/." "${IBM_CLASSES_DIR}/"
  log_ok "copied src/ibm/resources into ${IBM_CLASSES_DIR}"
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
# 6b. IBM suites - only when the IBM source set was compiled
#
# They run on a class path that DOES carry the SDK set, because they exercise the
# adapter. SelfTest itself stays core-only, so a broken IBM source set can never
# make the Goal 01 suites un-runnable.
# ---------------------------------------------------------------------------
if [ "${#IBM_TEST_SOURCES[@]}" -gt 0 ]; then
  IBM_TEST_RUN_CP="${IBM_TEST_CLASSES_DIR}${CP_SEP}${TEST_CLASSES_DIR}${CP_SEP}${CLASSES_DIR}${CP_SEP}${IBM_CLASSES_DIR}${CP_SEP}${IBM_CP}"
  if [ -n "${LIB_CP}" ]; then IBM_TEST_RUN_CP="${IBM_TEST_RUN_CP}${CP_SEP}${LIB_CP}"; fi

  # Discovered from the compiled output, not maintained as a second list. The
  # package name is fixed by the layout the IBM tests live in, and the class file
  # pattern matches the same public/no-arg/void convention SelfTest uses. A suite
  # that discovers nothing in an existing source tree is an error, so a rename
  # cannot quietly turn this step into a no-op.
  IBM_TEST_MAIN_CLASS="com.mraibo.cminsight.ibmtest.IbmAdapterTest"
  IBM_TEST_MAIN_REL="com/mraibo/cminsight/ibmtest/IbmAdapterTest.class"
  if [ ! -f "${IBM_TEST_CLASSES_DIR}/${IBM_TEST_MAIN_REL}" ]; then
    fail "${IBM_TEST_SRC_DIR} has ${#IBM_TEST_SOURCES[@]} source file(s) but no entry point ${IBM_TEST_MAIN_CLASS} was produced at ${IBM_TEST_MAIN_REL}. The IBM tests must expose the same dependency-free entry point the core suite does, or this step would silently test nothing."
  fi
  log_ok "running the IBM test suite: ${IBM_TEST_MAIN_CLASS}"
  set +e
  "${JAVA}" -cp "${IBM_TEST_RUN_CP}" "${IBM_TEST_MAIN_CLASS}"
  IBM_TEST_RC=$?
  set -e
  if [ "${IBM_TEST_RC}" -ne 0 ]; then
    printf 'ERROR: the IBM test suite %s failed with exit code %s.\n' "${IBM_TEST_MAIN_CLASS}" "${IBM_TEST_RC}" >&2
    exit "${IBM_TEST_RC}"
  fi
  log_ok "IBM test suite passed"
fi

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
# The IBM source set is packaged into the SAME jar and the SAME JVM, which is what
# keeps this a modular monolith: the adapter is optional at compile and at runtime,
# not a second deployable.
if [ -d "${IBM_CLASSES_DIR}" ]; then
  "${JAR}" uf "${JAR_FILE}" -C "${IBM_CLASSES_DIR}" .
  log_ok "packaged the IBM source set into ${JAR_FILE}"
fi
[ -s "${JAR_FILE}" ] || fail "the jar was not created correctly: ${JAR_FILE} is missing or empty."
printf '%s\n' "${APP_VERSION}" > "${BUILD_DIR}/.version"
log_ok "wrote ${BUILD_DIR}/.version (${APP_VERSION})"
log_ok "built ${JAR_FILE}"
log_ok "run it with: ./bin/cm-insight --config conf/application.properties   (or ./bin/start.sh)"
