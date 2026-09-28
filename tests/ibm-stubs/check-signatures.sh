#!/usr/bin/env bash
#
# CM Insight - pin the test-only IBM CM 8.7 compile stubs to a committed signature expectation.
#
#   tests/ibm-stubs/check-signatures.sh [--write] [--classes DIR] [--keep-classes]
#
# What it does:
#   1. compiles every file under tests/ibm-stubs with
#        javac --release 17 -encoding UTF-8 -Xlint:all
#      into a scratch directory that is NOT part of the packaged build tree;
#   2. runs `javap -public -constants` over every produced class, in ordinal (LC_ALL=C) order of the
#      fully qualified class name;
#   3. compares the concatenation against tests/ibm-stubs/EXPECTED_SIGNATURES.txt, ignoring the
#      '#'-prefixed header lines of the expectation file and normalising CRLF to LF.
#
# With --write it rewrites the expectation file instead of comparing (used only after a deliberate
# stub change).
#
# With --classes DIR it checks a stub classes directory somebody else already produced (this is what
# ./build.sh step 6 can pass) instead of compiling its own.
#
# Why pin it at all: the stubs must stay an honest mirror of the real cmbicmsdk81.jar surface. A
# silently widened or renamed stub would make src/ibm/java compile against an API that does not
# exist, and the failure would only show up on a machine that has the proprietary JAR.
#
# Message prefixes: "OK:" success, "ERROR:" fatal (exit != 0).
# Exit codes: 0 signatures match, 1 mismatch or compile failure, 2 usage or toolchain problem.

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tests/ibm-stubs/check-signatures.sh [--write] [--classes DIR] [--keep-classes]

  (no options)      compile the stubs and verify them against EXPECTED_SIGNATURES.txt
  --write           rewrite EXPECTED_SIGNATURES.txt from the current stubs
  --classes DIR     check the stub classes already compiled in DIR instead of compiling
  --keep-classes    keep the scratch classes directory (default: keep, it is .tools/... )
  -h, --help        this text

Environment:
  JAVA_HOME   JDK 17+ home; when unset, javac/javap come from PATH.
  STUB_SCRATCH_DIR  scratch classes directory (default: <repo>/.tools/ibm-stub-classes).
USAGE
}

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
fail_env() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
usage_fail() { printf 'ERROR: %s\n' "$*" >&2; usage >&2; exit 2; }

WRITE=0
CLASSES_DIR=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --write) WRITE=1 ;;
    --classes)
      shift
      [ "$#" -gt 0 ] || usage_fail "--classes needs a directory"
      CLASSES_DIR="$1"
      ;;
    --keep-classes) : ;;
    -h|--help) usage; exit 0 ;;
    *) usage_fail "unknown argument: $1" ;;
  esac
  shift
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)" || fail_env "cannot resolve the repository root"
STUB_SRC="${ROOT}/tests/ibm-stubs"
EXPECTED="${STUB_SRC}/EXPECTED_SIGNATURES.txt"

[ -d "${STUB_SRC}" ] || fail_env "missing ${STUB_SRC}"

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
  JAVAC="${JH}/bin/javac"
  JAVAP="${JH}/bin/javap"
else
  JAVAC="$(command -v javac 2>/dev/null || true)"
  JAVAP="$(command -v javap 2>/dev/null || true)"
fi
[ -n "${JAVAC}" ] && [ -x "${JAVAC}" ] || fail_env "javac not found (set JAVA_HOME to a JDK 17+)"
[ -n "${JAVAP}" ] && [ -x "${JAVAP}" ] || fail_env "javap not found (set JAVA_HOME to a JDK 17+)"

if [ -z "${CLASSES_DIR}" ]; then
  CLASSES_DIR="${STUB_SCRATCH_DIR:-${ROOT}/.tools/ibm-stub-classes}"
  rm -rf "${CLASSES_DIR}"
  mkdir -p "${CLASSES_DIR}"

  SOURCES=()
  while IFS= read -r file; do SOURCES+=("${file}"); done < <(find "${STUB_SRC}" -type f -name '*.java' | LC_ALL=C sort)
  [ "${#SOURCES[@]}" -gt 0 ] || fail "no stub sources found under ${STUB_SRC}"
  printf 'OK: compiling %s stub source file(s)\n' "${#SOURCES[@]}"
  "${JAVAC}" --release 17 -encoding UTF-8 -Xlint:all -d "${CLASSES_DIR}" "${SOURCES[@]}" \
    || fail "the compile stubs themselves do not compile"
else
  [ -d "${CLASSES_DIR}" ] || fail_env "--classes ${CLASSES_DIR} is not a directory"
fi

FQCNS="$(find "${CLASSES_DIR}" -type f -name '*.class' \
  | sed -e "s|^${CLASSES_DIR}/||" -e 's|\.class$||' -e 's|/|.|g' | LC_ALL=C sort)"
[ -n "${FQCNS}" ] || fail "no compiled stub classes under ${CLASSES_DIR}"

generate_body() {
  while IFS= read -r fqcn; do
    [ -n "${fqcn}" ] || continue
    "${JAVAP}" -public -constants -cp "${CLASSES_DIR}" "${fqcn}" | tr -d '\r'
  done <<< "${FQCNS}"
}

write_header() {
  cat <<'HEADER'
# CM Insight - pinned public signature expectation for the test-only IBM CM 8.7 compile stubs.
#
# WHAT THIS IS
#   The concatenated `javap -public -constants` output of every class produced from tests/ibm-stubs,
#   in ordinal (LC_ALL=C) order of the fully qualified class name. It is the contract that keeps the
#   stubs an honest mirror of the real proprietary surface: a stub member that is renamed, widened or
#   silently dropped changes this file and fails the build instead of letting src/ibm/java compile
#   against an API that does not exist.
#
# AUTHORITY ORDER
#   1. a real cmbicmsdk81.jar compile (lib/ibm/*.jar present) is authoritative over every stub here;
#   2. this file pins what the stubs currently claim;
#   3. harness/GOAL_02_IMPLEMENTATION_SPEC.md section 1 and IBM_CM87_SDK_API_SURFACE.md are the source
#      the signatures were copied from (real javap output against cmbicmsdk81.jar).
#
# HOW TO REGENERATE (canonical, do not hand-edit)
#   tests/ibm-stubs/check-signatures.sh --write
# which is exactly:
#   javac --release 17 -encoding UTF-8 -Xlint:all -d <scratch>/stub-classes $(find tests/ibm-stubs -name '*.java')
#   for each FQCN in LC_ALL=C sort order:  javap -public -constants -cp <scratch>/stub-classes <FQCN>
#
# HOW IT IS CHECKED
#   Every line starting with '#' (this header) is ignored, CRLF is normalised to LF, and the rest must
#   match the freshly generated output byte for byte.
#
# DELIBERATE PROPERTIES ENCODED BELOW (see tests/ibm-stubs/README.md)
#   * no close() member anywhere - the real SDK has none; teardown is disconnect() then destroy();
#   * no mutating member (commit/rollback/startTransaction/add/del/update/set*/clearCache) on any
#     stub, so a write call in src/ibm/java fails to compile rather than merely failing a regex guard;
#   * datastoreDef() returns dkDatastoreDef and datastoreAdmin() returns dkDatastoreAdmin - neither is
#     narrowed, so both casts in the adapter are structurally required;
#   * DKDatastoreICM.isConnected() declares no throws while dkDatastore.isConnected() declares
#     throws java.lang.Exception;
#   * the retention enums are nested enums (DKRetentionPolicyDefICM$DK_ICM_RETENTION_TYPE,
#     $DK_ICM_POLICY_TIME_UNIT, $DK_ICM_EXPIRATION_ACTION_TYPE), never int constants;
#   * the enum-constant -> CM numeric-code mapping is NOT recoverable from the class files: no numeric
#     value is pinned anywhere, and the adapter must render anything it cannot map as UNKNOWN(<value>).
HEADER
}

TMP_EXPECTED="$(mktemp)" || fail_env "cannot create a temporary file"
trap 'rm -f "${TMP_EXPECTED}"' EXIT

{
  write_header
  generate_body
} > "${TMP_EXPECTED}"

if [ "${WRITE}" -eq 1 ]; then
  cp "${TMP_EXPECTED}" "${EXPECTED}"
  printf 'OK: wrote %s (%s line(s))\n' "${EXPECTED}" "$(wc -l < "${EXPECTED}" | tr -d ' ')"
  exit 0
fi

[ -f "${EXPECTED}" ] || fail "missing ${EXPECTED}; regenerate it with: tests/ibm-stubs/check-signatures.sh --write"

ACTUAL="$(mktemp)"; EXPECTED_BODY="$(mktemp)"
trap 'rm -f "${TMP_EXPECTED}" "${ACTUAL}" "${EXPECTED_BODY}"' EXIT
grep -v '^#' "${TMP_EXPECTED}" > "${ACTUAL}"
grep -v '^#' "${EXPECTED}" | tr -d '\r' > "${EXPECTED_BODY}"

if cmp -s "${ACTUAL}" "${EXPECTED_BODY}"; then
  printf 'OK: %s stub class(es) match %s\n' "$(printf '%s\n' "${FQCNS}" | wc -l | tr -d ' ')" "tests/ibm-stubs/EXPECTED_SIGNATURES.txt"
  exit 0
fi

printf 'ERROR: the stub surface no longer matches EXPECTED_SIGNATURES.txt. Diff (expected vs actual):\n' >&2
diff -u "${EXPECTED_BODY}" "${ACTUAL}" >&2 || true
printf 'ERROR: if the change is deliberate, regenerate with: tests/ibm-stubs/check-signatures.sh --write\n' >&2
exit 1
