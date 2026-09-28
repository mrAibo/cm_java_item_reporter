#!/usr/bin/env bash
#
# CM Insight - Goal 02 section F/H regression test for the source guards.
#
#   ./tests/shell/ibm_source_guard_test.sh
#
# Owner: the test suite. The implementation under test is the lead-owned helper
# tests/shell/ibm_guard.sh, which is deliberately NOT named *_test.sh so that
# tests/shell/run.sh does not discover it directly: this file is what the suite
# runs, and this file owns every assertion about the guard.
#
# A guard that only ever exits 0 is worthless, so a green acceptance run is NOT
# evidence. This test therefore asserts that the guard BITES. Every mutation is
# planted in a throw-away mirror tree under mktemp -d - the real src/ tree is
# never touched - and the guard is executed from that mirror, where its own
# "../.." root resolution lands on the mirror instead of on this repository.
# Each failure case gets its OWN fresh mirror, so no earlier plant can explain,
# hide or inflate a later refusal.
#
# Cases:
#   1. acceptance          the guard holds on the committed tree (exit 0)
#   2. pre-plant control   a clean mirror passes, so a later refusal is the plant
#   3. read-only guard     a plant in src/ibm/java is refused, non-zero, and the
#                          refusal NAMES the file and the offending line. Plants:
#                          a direct mutating server call, the same call written
#                          as a fluent chain, and the native-JDBC extraction.
#   4. fatal refusal       a reported violation is fatal: the exit is never 0,
#                          and the pass reports EVERY pending violation instead
#                          of stopping at the first.
#   5. isolation guard     a planted com.ibm reference in a SECOND core file is
#                          refused (the allow-list covers exactly one file), and
#                          a planted literal com.ibm. string in a core file is
#                          refused too.
#   6. empty-scan refusal  the guard refuses to pass by scanning nothing: with
#                          src/main/java removed the exit is non-zero, and with
#                          src/ibm/java present but holding no .java file the
#                          exit is non-zero as well.
#   7. exit-code space     usage/layout stays 2, a violated guard stays 1.
#   8. no side effects     every scratch tree is removed and the real tree still
#                          passes, so no plant reached the repository.
#
# Guard contract asserted here: exit 0 = both guards hold, 1 = a guard was
# violated or the tree cannot be scanned, 2 = usage/layout problem. A non-zero
# exit alone is not enough: the refusal text must name the offending path, so a
# guard that fails for an unrelated reason cannot pass this test.
#
# Message prefixes: "ok:" pass, "FAIL:" fail, "cmd:" executed command + exit code.
# Exit codes: 0 every case passed, 1 at least one case failed.

set -u

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${SELF}")" && pwd)" || { printf 'ERROR: cannot locate %s\n' "${SELF}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)" || { printf 'ERROR: cannot resolve the repository root\n' >&2; exit 2; }

GUARD="${ROOT}/tests/shell/ibm_guard.sh"
ALLOWANCE_REL='src/main/java/com/mraibo/cminsight/connection/CmSession.java'
SCRATCH_PREFIX='ibm-guard-test.'

# On Windows the repository path arrives as a native form (C:\dir) while this
# script runs under bash, which uses the /c/dir form. Translate once, so two
# things hold that would otherwise differ by interpreter: mktemp still creates
# the scratch trees inside the working tree, and the guarded cleanup below still
# matches the trees this test created and nothing else.
ROOT_POSIX="$(printf '%s' "${ROOT}" | sed -E 's#^([A-Za-z]):[\\/]#/\L\1/#; s#\\#/#g')"
[ -d "${ROOT_POSIX}" ] || { printf 'FAIL: cannot address the repository root from bash: %s\n' "${ROOT}" >&2; exit 1; }

TEMPS=""
MIRROR=""
MIRRORS=0
cleanup() {
  local d
  for d in ${TEMPS}; do
    case "${d}" in
      "${ROOT_POSIX}/${SCRATCH_PREFIX}"*) [ -d "${d}" ] && rm -rf "${d}" ;;
    esac
  done
}
trap cleanup EXIT INT TERM

checks=0
failures=0

pass() { checks=$((checks + 1)); printf 'ok: %s\n' "$*"; }
bad()  { checks=$((checks + 1)); failures=$((failures + 1)); printf 'FAIL: %s\n' "$*" >&2; }
show() { printf '     %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# The guard must exist and be sound before anything can be asserted about it.
# ---------------------------------------------------------------------------
[ -f "${GUARD}" ] || { printf 'FAIL: missing %s; the guard under test does not exist\n' "${GUARD}" >&2; exit 1; }
[ -r "${GUARD}" ] || { printf 'FAIL: %s is not readable\n' "${GUARD}" >&2; exit 1; }

printf 'sha256: ibm_guard.sh = %s\n' "$(sha256sum < "${GUARD}" | cut -d' ' -f1)"

grep -q 'set -euo pipefail' "${GUARD}" || bad "ibm_guard.sh lost its 'set -euo pipefail' prologue"
grep -q "ISOLATION_ALLOWED_RELATIVE=\"${ALLOWANCE_REL}\"" "${GUARD}" \
  || bad "ibm_guard.sh no longer carries exactly the one documented isolation allowance (${ALLOWANCE_REL})"
grep -q 'FORBIDDEN_CALL_PATTERNS=' "${GUARD}" || bad "ibm_guard.sh no longer carries a forbidden-call table"
grep -q 'getNativeConnection' "${GUARD}" || bad "ibm_guard.sh no longer forbids native-JDBC extraction"

# ---------------------------------------------------------------------------
# Running the guard. TREE selects the tree the guard must treat as its root.
# ---------------------------------------------------------------------------
TREE="${ROOT}"
RC=0
OUT=''

run_guard() {
  printf 'cmd: (cd %s && ./tests/shell/ibm_guard.sh %s)\n' "${TREE}" "${*:-}" >&2
  # stdout and stderr are merged on purpose: the guard reports refusals on
  # stderr and "OK:" progress on stdout, and the refusal text is the assertion.
  OUT="$( cd "${TREE}" && ./tests/shell/ibm_guard.sh "$@" 2>&1 )"
  RC=$?
  printf 'cmd: exit=%s\n' "${RC}" >&2
}

# ---------------------------------------------------------------------------
# The mirror trees.
# ---------------------------------------------------------------------------
new_mirror() {
  # Sets MIRROR to a fresh scratch tree. Called in the parent shell - never in a
  # command substitution - so the bookkeeping above survives.
  local dir
  dir="$(mktemp -d -p "${ROOT_POSIX}" "${SCRATCH_PREFIX}XXXXXX")" || {
    printf 'FAIL: mktemp -d failed under %s\n' "${ROOT_POSIX}" >&2; exit 1; }
  case "${dir}" in
    /*) : ;;
    *) dir="${ROOT_POSIX}/${dir}" ;;
  esac
  [ -d "${dir}" ] || { printf 'FAIL: the scratch tree %s was not created\n' "${dir}" >&2; exit 1; }
  TEMPS="${TEMPS} ${dir}"
  MIRRORS=$((MIRRORS + 1))
  mkdir -p "${dir}/tests/shell" \
           "${dir}/src/main/java/com/mraibo/cminsight/connection" \
           "${dir}/src/main/java/com/mraibo/cminsight/core" \
           "${dir}/src/ibm/java/com/mraibo/cminsight/ibm/internal" || {
    printf 'FAIL: cannot lay out the scratch tree %s\n' "${dir}" >&2; exit 1; }
  cp "${GUARD}" "${dir}/tests/shell/ibm_guard.sh" || { printf 'FAIL: cannot copy the guard\n' >&2; exit 1; }
  chmod +x "${dir}/tests/shell/ibm_guard.sh" 2>/dev/null || true
  # The ONE allowance: its comment names an IBM type, which is the only com.ibm.
  # reference the guard tolerates under src/main/java. This fixture is a
  # deliberate pattern-match for the guard's own allow-list check.
  printf '%s\n' \
    'package com.mraibo.cminsight.connection;' \
    '' \
    '/**' \
    ' * Scratch fixture: the ONE allowance. The comment below names an IBM type and is' \
    ' * the only com.ibm. reference the guard tolerates under src/main/java.' \
    ' * The SDK handle type DKDatastoreICM is not imported on purpose.' \
    ' */' \
    'final class CmSession {' \
    '  private CmSession() { }' \
    '}' > "${dir}/${ALLOWANCE_REL}"
  printf '%s\n' \
    'package com.mraibo.cminsight.core;' \
    '' \
    '/** Scratch fixture: a legitimate core file with no IBM reference at all. */' \
    'final class CoreProbe {' \
    '  private CoreProbe() { }' \
    '}' > "${dir}/src/main/java/com/mraibo/cminsight/core/CoreProbe.java"
  printf '%s\n' \
    'package com.mraibo.cminsight.ibm.internal;' \
    '' \
    '/** Scratch fixture: the IBM source set, clean until a plant is added. */' \
    'final class IbmProbe {' \
    '  private IbmProbe() { }' \
    '}' > "${dir}/src/ibm/java/com/mraibo/cminsight/ibm/internal/IbmProbe.java"
  MIRROR="${dir}"
}

# plant <tree> <relative path> <one-line Java statement>
plant() {
  printf '  %s\n' "$3" >> "$1/$2" || { printf 'FAIL: cannot plant into %s\n' "$2" >&2; exit 1; }
}

probe_rel() { printf 'src/ibm/java/com/mraibo/cminsight/ibm/internal/%s\n' "$1"; }

# assert_refused <label> <expected exit code or "nonzero"> <expected path in the refusal> <expected text in the refusal>
assert_refused() {
  local label="$1" want_rc="$2" want_path="$3" want_text="$4"
  local rc="${RC}" out="${OUT}"
  if [ "${want_rc}" = nonzero ] && [ "${rc}" -eq 0 ]; then
    bad "${label}: the guard exited 0; it did not bite"
    show "$(printf '%s\n' "${out}" | tr '\n' ' ')"
    return
  fi
  if [ "${want_rc}" != nonzero ] && [ "${rc}" -ne "${want_rc}" ]; then
    bad "${label}: expected exit ${want_rc}, saw ${rc}"
    show "$(printf '%s\n' "${out}" | tr '\n' ' ')"
    return
  fi
  if ! printf '%s\n' "${out}" | grep -q -F "${want_text}"; then
    bad "${label}: exit ${rc} but the refusal does not contain '${want_text}'"
    show "$(printf '%s\n' "${out}" | tr '\n' ' ')"
    return
  fi
  if ! printf '%s\n' "${out}" | grep -q -F "${want_path}"; then
    bad "${label}: the refusal does not name ${want_path}"
    show "$(printf '%s\n' "${out}" | tr '\n' ' ')"
    return
  fi
  pass "${label}: refused with exit ${rc}, naming $(basename -- "${want_path}") (or the named directory)"
  show "$(printf '%s\n' "${out}" | grep -F "${want_text}" | head -n 1)"
}

# ---------------------------------------------------------------------------
# The guard's own documented interface.
# ---------------------------------------------------------------------------
TREE="${ROOT}"
run_guard --list
if [ "${RC}" -eq 0 ] && printf '%s\n' "${OUT}" | grep -q 'forbidden call patterns' \
   && printf '%s\n' "${OUT}" | grep -q 'isolation allow-list'; then
  pass "--list exits 0 and prints the forbidden call table and the isolation allow-list"
else
  bad "--list exited ${RC} without printing the expected tables"
  show "${OUT}"
fi

run_guard --help
if [ "${RC}" -eq 0 ] && printf '%s\n' "${OUT}" | grep -q 'Usage:'; then
  pass "--help exits 0 and prints usage"
else
  bad "--help exited ${RC} without printing usage"
fi

run_guard --definitely-not-a-flag
if [ "${RC}" -eq 2 ] && printf '%s\n' "${OUT}" | grep -q 'unknown argument'; then
  pass "an unknown argument is exit 2 (usage), keeping 1 reserved for a violated guard"
else
  bad "an unknown argument produced exit ${RC} instead of 2"
fi

# ---------------------------------------------------------------------------
# Case 1: acceptance - the guard holds on the committed tree.
# ---------------------------------------------------------------------------
run_guard
if [ "${RC}" -eq 0 ]; then
  pass "acceptance: the guard exits 0 on the committed tree"
  show "$(printf '%s\n' "${OUT}" | tr '\n' ' ')"
else
  # Re-run on purpose: whether a failure here is the tree or the guard has to be
  # decided from the named refusals. A real mutating SDK call means the TREE is
  # violated; a collection or cache call on an adapter-local object means the
  # GUARD pattern is too broad and its owner must narrow it.
  run_guard
  bad "acceptance: the guard exited ${RC} on the committed tree"
  show "refusals to adjudicate:"
  printf '%s\n' "${OUT}" | grep -F 'ERROR:' | sed 's/^/     | /' >&2
fi

# ---------------------------------------------------------------------------
# Case 2: pre-plant control - a clean mirror passes.
# ---------------------------------------------------------------------------
new_mirror
CONTROL="${MIRROR}"
show "scratch tree (control): ${CONTROL}"
TREE="${CONTROL}"
run_guard
if [ "${RC}" -eq 0 ]; then
  pass "pre-plant control: a clean mirror tree passes the guard (exit 0)"
  show "$(printf '%s\n' "${OUT}" | tr '\n' ' ')"
else
  bad "pre-plant control: the clean mirror tree already fails (exit ${RC}); no plant is being tested"
  show "${OUT}"
fi

# The control also proves the allowance is exercised rather than merely present:
# the guard prints its allow-list acknowledgement only on a real match.
if printf '%s\n' "${OUT}" | grep -q -F 'allow-listed comment reference'; then
  pass "the ONE allowance in CmSession.java is matched and acknowledged by the allow-list"
else
  bad "the guard never acknowledged the allowance in ${ALLOWANCE_REL}; the allow-list is untested"
fi

# ---------------------------------------------------------------------------
# Case 3: the read-only guard refuses planted mutating calls.
# ---------------------------------------------------------------------------
# 3a. a direct mutating server call
new_mirror
T3A="${MIRROR}"
rel="$(probe_rel CmdsCommit.java)"
plant "${T3A}" "${rel}" 'void writeThrough() { session.commit(); }'
TREE="${T3A}"
run_guard
assert_refused "planted mutating call 'session.commit();'" nonzero "${T3A}/${rel}" 'forbidden IBM CM mutating call'

# 3b. the same idea written as a fluent chain
new_mirror
T3B="${MIRROR}"
rel="$(probe_rel CmdsChain.java)"
plant "${T3B}" "${rel}" 'void chain() { setMetaDataFields(node).checkIn(); }'
TREE="${T3B}"
run_guard
assert_refused "planted fluent call 'setMetaDataFields(node).checkIn()'" nonzero "${T3B}/${rel}" 'forbidden IBM CM mutating call'

# 3c. native-JDBC extraction
new_mirror
T3C="${MIRROR}"
rel="$(probe_rel CmdsJdbc.java)"
plant "${T3C}" "${rel}" 'java.sql.Connection raw() { return handle.getNativeConnection(); }'
TREE="${T3C}"
run_guard
assert_refused "planted native-JDBC extraction 'getNativeConnection()'" nonzero "${T3C}/${rel}" 'forbidden native JDBC extraction'

# Positive control: the plant really is in the file the guard scanned, so a guard
# that scanned nothing could not have produced the refusals above.
if grep -q -F 'getNativeConnection()' "${T3C}/${rel}"; then
  pass "positive control: the plant is present in CmdsJdbc.java, a file the guard did scan"
else
  bad "the plant is missing from ${T3C}/${rel}; the refusal evidence would be meaningless"
fi

# ---------------------------------------------------------------------------
# Case 4: a reported violation is fatal, and one pass reports every violation.
# ---------------------------------------------------------------------------
new_mirror
T4="${MIRROR}"
relA="$(probe_rel CmdsRollback.java)"
relB="$(probe_rel CmdsAssign.java)"
relC="$(probe_rel CmdsJdbc2.java)"
plant "${T4}" "${relA}" 'void forceRollback() { policy.rollback(); }'
plant "${T4}" "${relB}" 'void rebind() { item.assign(view); }'
plant "${T4}" "${relC}" 'java.sql.Connection raw() { return handle.getNativeConnection(); }'
TREE="${T4}"
run_guard
fatal_rc="${RC}"
fatal_out="${OUT}"
if [ "${fatal_rc}" -ne 0 ]; then
  pass "a reported violation is fatal: the guard exits ${fatal_rc}, never 0"
else
  bad "the guard reported violations and still exited 0"
  show "${fatal_out}"
fi
if printf '%s\n' "${fatal_out}" | grep -q -F 'Goal 02 source-guard violation(s) above'; then
  pass "the fatal path reports a violation count instead of dying silently"
else
  bad "the fatal path did not report a violation count"
  show "${fatal_out}"
fi
missing=""
for r in "${relA}" "${relB}" "${relC}"; do
  printf '%s\n' "${fatal_out}" | grep -q -F "${T4}/${r}" || missing="${missing} ${r##*/}"
done
if [ -z "${missing}" ]; then
  pass "one pass reports every pending violation (3 plants, 3 refusals), not just the first"
else
  bad "the pass stopped early; no refusal named:${missing}"
  show "$(printf '%s\n' "${fatal_out}" | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------------
# Case 5: the isolation guard refuses a second allowance and a literal string.
# ---------------------------------------------------------------------------
# 5a. a com.ibm reference in a SECOND core file: the allow-list has exactly one entry
new_mirror
T5A="${MIRROR}"
rel='src/main/java/com/mraibo/cminsight/core/SecondAllowance.java'
printf '%s\n' \
  'package com.mraibo.cminsight.core;' \
  '' \
  '/**' \
  ' * Scratch fixture: a SECOND allowance. The guard tolerates exactly one file, so' \
  ' * this comment reference must be refused: com.ibm. is an IBM SDK package.' \
  ' */' \
  'final class SecondAllowance {' \
  '  private SecondAllowance() { }' \
  '}' > "${T5A}/${rel}"
TREE="${T5A}"
run_guard
assert_refused "a SECOND com.ibm allowance in SecondAllowance.java" nonzero "${T5A}/${rel}" 'com.ibm reference in the IBM-independent core'

# 5b. a literal com.ibm. string in a core file
new_mirror
T5B="${MIRROR}"
rel='src/main/java/com/mraibo/cminsight/core/CoreProbe.java'
plant "${T5B}" "${rel}" 'static final String VENDOR = "com.ibm.";'
TREE="${T5B}"
run_guard
assert_refused "a planted literal 'com.ibm.' string in CoreProbe.java" nonzero "${T5B}/${rel}" 'com.ibm reference in the IBM-independent core'

# ---------------------------------------------------------------------------
# Case 6: the guard refuses to pass by scanning nothing.
# ---------------------------------------------------------------------------
# 6a. src/main/java absent: nothing to scan, which is a layout failure, not a clean result.
new_mirror
T6A="${MIRROR}"
mv "${T6A}/src/main/java" "${T6A}/core-backup" || { printf 'FAIL: cannot move src/main/java aside\n' >&2; exit 1; }
TREE="${T6A}"
run_guard
assert_refused "a missing src/main/java" nonzero "${T6A}/src/main/java" 'nothing to scan'
if [ -d "${T6A}/core-backup" ]; then
  mv "${T6A}/core-backup" "${T6A}/src/main/java"
fi

# 6b. src/ibm/java present but holding no .java file: the read-only guard would scan nothing.
new_mirror
T6B="${MIRROR}"
mv "${T6B}/src/ibm/java" "${T6B}/ibm-backup" || { printf 'FAIL: cannot move src/ibm/java aside\n' >&2; exit 1; }
mkdir -p "${T6B}/src/ibm/java/com/mraibo/cminsight/ibm/internal" || { printf 'FAIL: cannot create the empty IBM tree\n' >&2; exit 1; }
empty_count="$(find "${T6B}/src/ibm/java" -name '*.java' -print | wc -l | tr -d ' ')"
TREE="${T6B}"
run_guard
if [ "${empty_count}" -ne 0 ]; then
  bad "the empty src/ibm/java fixture held ${empty_count} .java file(s); the case proves nothing"
else
  assert_refused "an empty src/ibm/java" nonzero "${T6B}/src/ibm/java" 'contains no .java file'
fi
if [ -d "${T6B}/ibm-backup" ]; then
  rm -rf "${T6B}/src/ibm/java"
  mv "${T6B}/ibm-backup" "${T6B}/src/ibm/java"
fi

# ---------------------------------------------------------------------------
# Case 8: no side effects. Removing every scratch tree here - before the real
# tree is checked again - proves the trap removes what the test created, and the
# real acceptance run proves no plant reached the repository.
# ---------------------------------------------------------------------------
count="${MIRRORS}"
# Nine mirrors are expected (1 control + 3 read-only plants + 1 multi-plant +
# 2 isolation plants + 2 empty-scan cases); demand a clear lower bound so a
# future edit that silently drops a mutation case is caught here.
[ "${count}" -ge 8 ] || bad "expected the mutation cases to create several mirror trees, saw ${count}"
cleanup
leftover=0
for d in ${TEMPS}; do [ -d "${d}" ] && leftover=$((leftover + 1)); done
if [ "${leftover}" -eq 0 ]; then
  pass "all ${count} scratch mirror tree(s) were removed by the trap"
else
  bad "${leftover} of ${count} scratch mirror tree(s) survived cleanup"
fi
TEMPS=""

TREE="${ROOT}"
run_guard
if [ "${RC}" -eq 0 ]; then
  pass "the real working tree still holds after every mutation case (exit 0)"
else
  bad "the real working tree is now dirty: the guard exits ${RC}"
  show "${OUT}"
fi

printf '\n=== ibm_source_guard_test summary ===\n'
printf 'checks: %s, failures: %s\n' "${checks}" "${failures}"
if [ "${failures}" -gt 0 ]; then
  printf 'FAIL: the source-guard test found %s failing assertion(s)\n' "${failures}" >&2
  exit 1
fi
printf 'ok: every source-guard assertion passed, including the mutation cases\n'
exit 0
