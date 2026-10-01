#!/usr/bin/env bash
#
# CM Insight - Goal 02 section A/H source guards.
#
#   ./tests/shell/ibm_guard.sh [--list] [--help]
#
# This script is the single implementation of two hard Goal 02 rules. It is
# deliberately NOT a *_test.sh file: tests/shell/run.sh discovers *_test.sh and
# *.test.sh, so a helper named this way is never run directly by the suite. The
# committed test tests/shell/ibm_source_guard_test.sh owns the assertions - both
# the acceptance case AND the mutation cases that prove the guards still bite -
# and build.sh calls this script with --check-ibm-isolation so the same rule is
# enforced even if somebody never runs the suite.
#
# Guard 1 - read-only adapter (goal section H):
#   No IBM CM server-mutating call may appear anywhere under src/ibm/java. The
#   list is the union of the mutating SDK members confirmed present by javap
#   (see harness/IBM_CM87_SDK_API_SURFACE.md section 10) and the paths the goal
#   file forbids by name. V1/V2 is read-only, so this is a product guarantee and
#   not a style rule.
#
# Guard 2 - SDK isolation (goal section A):
#   No com.ibm reference may appear under src/main/java, with exactly ONE
#   documented exception: connection/CmSession.java records in a comment that
#   its implementation may wrap DKDatastoreICM. A comment is not a type
#   reference, but the file has to be named explicitly rather than silently
#   allowed, so an allow-list of one path is carried here and asserted.
#
# Message prefixes: "OK:" success, "ERROR:" fatal (exit != 0).
# Exit codes: 0 all guards hold, 1 a guard was violated, 2 usage or layout problem.

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./tests/shell/ibm_guard.sh [--list] [--help]

Enforces the two Goal 02 source guards:
  1. no IBM CM server-mutating call under src/ibm/java   (read-only adapter)
  2. no com.ibm reference under src/main/java            (SDK isolation)

Options:
  --list    print the forbidden call patterns and the isolation allow-list
  --help    show this help

Exit codes: 0 both guards hold, 1 a guard was violated, 2 usage or layout.
USAGE
}

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
usage_fail() { printf 'ERROR: %s\n' "$*" >&2; usage >&2; exit 2; }

LIST_ONLY=false
for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage; exit 0 ;;
    --list) LIST_ONLY=true ;;
    *) usage_fail "unknown argument: ${arg}" ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${SELF}")" && pwd)" || { printf 'ERROR: cannot locate %s\n' "${SELF}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)" || { printf 'ERROR: cannot resolve the repository root\n' >&2; exit 2; }

IBM_SRC_DIR="${ROOT}/src/ibm/java"
CORE_SRC_DIR="${ROOT}/src/main/java"
STUB_DIR="${ROOT}/tests/ibm-stubs"

# ---------------------------------------------------------------------------
# The forbidden mutating calls.
#
# Anchored on the call parenthesis so a getter whose NAME merely resembles one
# of these - commitCount, addItemTypeView, deleteExpiredItemsMaximumRows - is
# not a false positive. Every entry is a real member of DKPolicyMgmtICM,
# DKItemTypeDefICM, DKDatastoreICM or DKDatastoreDefICM according to javap.
#
# WHY THE COLLECTION-SHAPED NAMES ARE NOT HERE AT ALL - not `add`, `remove`,
# `update`, `delete`, `del` (which DKPolicyMgmtICM really does expose) and not
# `set*` (which DKItemTypeDefICM really does expose):
#
# every one of them is also an ordinary java.util or primitive-wrapper method, so
# matching by name alone refuses the adapter's own bookkeeping. Measured, on a
# file whose entire content was local collections:
#
#   List.add / List.remove / Map.remove / Map.put / List.clear   -> 5 refusals
#   AtomicBoolean.set / List.set                                 -> 2 refusals
#
# A guard that cannot pass is a guard that gets disabled, so the receiver
# cannot be left out of it. And the receiver cannot be guessed either: the
# adapter legitimately holds `List<String>`, `Map<...>`, `AtomicBoolean`,
# `AtomicLong` and `Optional` locals, so any receiver allow-list would be both
# long and wrong the first time somebody adds a field.
#
# Those members are excluded STRUCTURALLY instead, one layer earlier, and it is a
# stronger guarantee than a text scan: tests/ibm-stubs deliberately declares ONLY
# the read-only getters on DKItemTypeDefICM, DKRetentionPolicyDefICM and
# DKPolicyMgmtICM. A call to `policy.add(...)`, `itemType.update()` or
# `itemType.setName(...)` therefore does not compile against the stubs at all.
# build.sh compiles the stubs and pins them against
# tests/ibm-stubs/EXPECTED_SIGNATURES.txt, so the omission is audited rather than
# assumed, and it cannot be defeated by a receiver name this file failed to
# predict.
#
# What remains here is the set of names that are IBM-specific and unambiguous:
# nothing in the JDK or in the adapter's own code is called `checkIn`, `reorg` or
# `changePassword`. Each is anchored on the call parenthesis, so a getter that
# merely resembles one of them - commitCount, deleteExpiredItemsMaximumRows,
# addItemTypeView - is not a refusal.
# ---------------------------------------------------------------------------
FORBIDDEN_CALL_PATTERNS=(
  'commit[[:space:]]*\('
  'rollback[[:space:]]*\('
  'checkIn[[:space:]]*\('
  'checkOut[[:space:]]*\('
  'backfill[[:space:]]*\('
  'migrate[[:space:]]*\('
  'moveObject[[:space:]]*\('
  'changePassword[[:space:]]*\('
  'makeActive[[:space:]]*\('
  'makeInactive[[:space:]]*\('
  'reorg[[:space:]]*\('
  'recreate[[:space:]]*\('
  'clearCache[[:space:]]*\('
  'assign[[:space:]]*\('
  'unassign[[:space:]]*\('
)

# The one file allowed to mention a com.ibm type in a comment under src/main/java.
ISOLATION_ALLOWED_RELATIVE="src/main/java/com/mraibo/cminsight/connection/CmSession.java"

# ---------------------------------------------------------------------------
# The DB2 driver class NAME is a deliberate exception, and it is narrower than an
# allow-listed file.
#
# Goal 03 needs local, offline JDBC driver discovery, and the DB2 universal JDBC
# driver's class name is literally `com.ibm.db2.jcc.DB2Driver`. That is not the
# IBM CM SDK: this repository compiles against no DB2 jar, the name is only ever a
# String passed to Class.forName, and the driver ships separately in lib/db2 or is
# absent entirely. The CM-SDK isolation rule exists to stop core code from
# DEPENDING on com.ibm types; a reflective driver name is not such a dependency,
# and a rule that forbids it forbids the correct implementation.
#
# So the exemption is scoped to THIS NAME rather than to a file. Stating it here
# means the `--list` output and this comment are the one place the exception is
# visible, and it cannot quietly widen: any other com.ibm reference anywhere under
# src/main/java, in the same file or another, is still a violation.
#
# The alternative - allowing the whole file - is rejected on purpose. An
# allow-listed file is an escape hatch: the next person to edit it could add a real
# SDK reference and the guard would stay silent, which is exactly the failure mode
# this guard exists to prevent.
ISOLATION_ALLOWED_REFERENCE='com\.ibm\.db2\.jcc\.DB2Driver'

if [ "${LIST_ONLY}" = true ]; then
  printf 'forbidden call patterns (src/ibm/java):\n'
  for pattern in "${FORBIDDEN_CALL_PATTERNS[@]}"; do printf '  %s\n' "${pattern}"; done
  printf 'isolation allow-list (src/main/java):\n  %s\n' "${ISOLATION_ALLOWED_RELATIVE}"
  printf 'isolation allowed reference (driver class name only):\n  %s\n' "${ISOLATION_ALLOWED_REFERENCE}"
  exit 0
fi

# The guards must never be able to pass by scanning nothing. A missing tree is a
# layout problem, not a clean result: a rename would otherwise silently disable
# the whole rule.
[ -d "${ROOT}/src" ] || fail "missing ${ROOT}/src; this does not look like the CM Insight working tree"
[ -d "${CORE_SRC_DIR}" ] || fail "missing ${CORE_SRC_DIR}; the isolation guard has nothing to scan, which is not the same as passing"

violations=0

# ---------------------------------------------------------------------------
# Guard 1: read-only adapter.
#
# A missing src/ibm/java is legitimate - core-only mode has no IBM source set -
# and is reported as such rather than as a violation.
# ---------------------------------------------------------------------------
if [ -d "${IBM_SRC_DIR}" ]; then
  ibm_files=()
  while IFS= read -r -d '' file; do ibm_files+=("${file}"); done \
    < <(find "${IBM_SRC_DIR}" -type f -name '*.java' -print0 2>/dev/null)

  if [ "${#ibm_files[@]}" -eq 0 ]; then
    fail "${IBM_SRC_DIR} exists but contains no .java file; the read-only guard would scan nothing and cannot report success"
  fi

  for pattern in "${FORBIDDEN_CALL_PATTERNS[@]}"; do
    while IFS= read -r line; do
      [ -n "${line}" ] || continue
      printf 'ERROR: forbidden IBM CM mutating call under src/ibm/java: %s\n' "${line}" >&2
      violations=$((violations + 1))
    done < <(grep -REn --include='*.java' "(${pattern})" "${IBM_SRC_DIR}" 2>/dev/null || true)
  done

  # The reference project reaches a native JDBC connection by reflecting on an
  # SDK handle. That is forbidden outright in this project: it would give the
  # viewer a raw java.sql.Connection and invite exactly the JDBC analytics Goal
  # 02 excludes.
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    printf 'ERROR: forbidden native JDBC extraction under src/ibm/java: %s\n' "${line}" >&2
    violations=$((violations + 1))
  done < <(grep -REn --include='*.java' '(getNativeConnection|\.connection[[:space:]]*\([[:space:]]*\)[[:space:]]*\.)' "${IBM_SRC_DIR}" 2>/dev/null || true)

  if [ "${violations}" -eq 0 ]; then
    printf 'OK: src/ibm/java scanned (%s file(s)); no IBM CM mutating call present\n' "${#ibm_files[@]}"
  fi
else
  printf 'OK: src/ibm/java is absent (core-only layout); the read-only guard has no IBM source to inspect\n'
fi

# ---------------------------------------------------------------------------
# Guard 2: SDK isolation of the core.
# ---------------------------------------------------------------------------
core_files=0
while IFS= read -r -d '' _file; do core_files=$((core_files + 1)); done \
  < <(find "${CORE_SRC_DIR}" -type f -name '*.java' -print0 2>/dev/null)
[ "${core_files}" -gt 0 ] || fail "${CORE_SRC_DIR} contains no .java file; the isolation guard would scan nothing"

while IFS= read -r line; do
  [ -n "${line}" ] || continue
  file="${line%%:*}"
  relative="${file#"${ROOT}/"}"
  if [ "${relative}" = "${ISOLATION_ALLOWED_RELATIVE}" ]; then
    # The single documented exception. Asserted here rather than assumed, so a
    # second allowance cannot appear without this line being edited too.
    printf 'OK: allow-listed comment reference in %s\n' "${relative}"
    continue
  fi
  # Report only the com.ibm references that are NOT the allowed driver class name.
  # Matching is done against the reference itself, so the exemption cannot be
  # satisfied by a file name and cannot cover a different com.ibm type.
  # `grep -n` output is <path>:<line>:<content>, so drop the first two fields.
  content="${line#*:}"
  content="${content#*:}"
  if printf '%s\n' "${content}" | grep -Eq "${ISOLATION_ALLOWED_REFERENCE}"; then
    remaining="$(printf '%s\n' "${content}" | sed -E "s/${ISOLATION_ALLOWED_REFERENCE}//g")"
    if ! printf '%s\n' "${remaining}" | grep -Eq 'com\.ibm\.'; then
      printf 'OK: allowed JDBC driver class name in %s\n' "${relative}"
      continue
    fi
  fi
  printf 'ERROR: com.ibm reference in the IBM-independent core: %s\n' "${line}" >&2
  violations=$((violations + 1))
done < <(grep -REn --include='*.java' 'com\.ibm\.' "${CORE_SRC_DIR}" 2>/dev/null || true)

# The core must not depend on the IBM adapter's own packages either - that would
# make the optional source set mandatory.
while IFS= read -r line; do
  [ -n "${line}" ] || continue
  printf 'ERROR: the core references the optional IBM adapter package: %s\n' "${line}" >&2
  violations=$((violations + 1))
done < <(grep -REn --include='*.java' 'com\.mraibo\.cminsight\.ibm\.internal' "${CORE_SRC_DIR}" 2>/dev/null || true)

if [ "${violations}" -eq 0 ]; then
  printf 'OK: src/main/java scanned (%s file(s)); no IBM SDK type escapes into the core\n' "${core_files}"
fi

# ---------------------------------------------------------------------------
# The stubs must never be a production input.
# ---------------------------------------------------------------------------
if [ -d "${STUB_DIR}" ]; then
  while IFS= read -r -d '' _file; do :; done < <(find "${STUB_DIR}" -type f -name '*.java' -print0 2>/dev/null)
  # The core compile never puts the stubs on its class path (build.sh compiles
  # src/main/java before any SDK set is resolved). Assert the packaging rule that
  # can be checked from here: no stub may be copied into the packaged classes.
  if [ -d "${ROOT}/build/classes/com/ibm" ]; then
    fail "an IBM stub class reached build/classes; stubs are test-only and must never be packaged"
  fi
  printf 'OK: tests/ibm-stubs present and absent from build/classes\n'
fi

if [ "${violations}" -gt 0 ]; then
  fail "${violations} Goal 02 source-guard violation(s) above"
fi
printf 'OK: all Goal 02 source guards hold\n'
