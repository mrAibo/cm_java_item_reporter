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
#   2. pre-plant control   a clean mirror passes, so a later refusal is the plant,
#                          and the one allow-listed file is really matched
#   3. read-only guard     a plant in src/ibm/java is refused, non-zero, and the
#                          refusal NAMES the file and the offending line. Plants:
#                          a direct mutating server call, the same call written
#                          as a fluent chain, and the native-JDBC extraction.
#   4. fatal refusal       a reported violation is fatal: the exit is never 0,
#                          and the pass reports EVERY pending violation instead
#                          of stopping at the first.
#   5. isolation guard     a planted com.ibm reference in a SECOND core file is
#                          refused (the allow-list covers exactly one file), a
#                          planted literal com.ibm. string in a core file is
#                          refused too, and - the STRUCTURAL half of the read-only
#                          rule - no mutating member appears in the pinned stub
#                          expectation, in the stub sources, or in the doc that
#                          defines the mutating set (see case 5c).
#   6. false positives     adapter-local java.util / wrapper calls (list.add,
#                          map.remove, list.clear, map.put, list.set,
#                          atomicBoolean.set) are NOT refused. This is the
#                          assertion that stops a future "fix" of a false positive
#                          by deleting the guard.
#   7. every pattern bites every pattern the guard still declares must still be
#                          refused and named - the other half of case 6 - and a
#                          getter whose NAME merely resembles a forbidden call
#                          (commitCount, deleteExpiredItemsMaximumRows) must not
#                          be refused.
#   8. empty-scan refusal  the guard refuses to pass by scanning nothing: with
#                          src/main/java removed the exit is non-zero, and with
#                          src/ibm/java present but holding no .java file the
#                          exit is non-zero as well.
#   9. exit-code space     usage/layout stays 2, a violated guard stays 1.
#  10. no side effects     every scratch tree is removed and the real tree still
#                          passes, so no plant reached the repository.
#
# Two layers, so two kinds of assertion: the TEXT layer (the 15 unambiguous
# patterns in ibm_guard.sh) is exercised by planting calls, and the STRUCTURAL
# layer (the stubs declare only read-only getters, so policy.add / itemType.update
# / itemType.setName do not compile) is exercised by reading the pinned artifacts.
# A mutating SDK call is never asserted by COMPILING it - this suite must compile.
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
# script runs under bash, which uses the /c/dir form. Translate once, so the sweep
# and the fallback below address the same directory the interpreter does.
ROOT_POSIX="$(printf '%s' "${ROOT}" | sed -E 's#^([A-Za-z]):[\\/]#/\L\1/#; s#\\#/#g')"
[ -d "${ROOT_POSIX}" ] || { printf 'FAIL: cannot address the repository root from bash: %s\n' "${ROOT}" >&2; exit 1; }

# Scratch trees live in the SYSTEM temp directory, never in the working tree. A
# test killed hard enough that no EXIT trap fires must not leave an untracked
# mirror of src/ and tests/ in the repository, where git add -A would sweep it into
# a commit. The guard takes its root from the tree it is run in, so a mirror under
# /tmp behaves exactly like one in the repo. The repo root remains the fallback for
# a locked-down environment, and which one is in use is printed below.
SCRATCH_PARENT=""
if [ -n "${TMPDIR:-}" ] && mkdir -p "${TMPDIR}" 2>/dev/null && touch "${TMPDIR}/.cm-insight-write-probe" 2>/dev/null; then
  rm -f "${TMPDIR}/.cm-insight-write-probe"
  SCRATCH_PARENT="${TMPDIR}"
elif mkdir -p /tmp 2>/dev/null && touch /tmp/.cm-insight-write-probe 2>/dev/null; then
  rm -f /tmp/.cm-insight-write-probe
  SCRATCH_PARENT=/tmp
elif touch "${ROOT_POSIX}/.cm-insight-write-probe" 2>/dev/null; then
  rm -f "${ROOT_POSIX}/.cm-insight-write-probe"
  SCRATCH_PARENT="${ROOT_POSIX}"
fi
[ -n "${SCRATCH_PARENT}" ] || { printf 'FAIL: no writable scratch location (tried %s, /tmp, %s)\n' "${TMPDIR:-<unset>}" "${ROOT_POSIX}" >&2; exit 1; }

# Every directory this test may ever own, in both possible locations. The sweep and
# the residual report below use this list, so a leftover is caught in either place.
mirror_dirs() {
  local parent d
  for parent in "${SCRATCH_PARENT}" "${ROOT_POSIX}"; do
    for d in "${parent}"/${SCRATCH_PREFIX}*; do
      [ -d "${d}" ] || continue
      printf '%s\n' "${d}"
    done
  done
}

# Defensive sweep: a run killed before its trap fired self-heals here on the next
# run instead of accumulating. It removes only directories named with this test's
# own prefix, and only directories - never a file and never a symlink.
swept=0
while IFS= read -r stale; do
  [ -n "${stale}" ] || continue
  [ -L "${stale}" ] && continue
  case "${stale}" in
    "${SCRATCH_PARENT}/${SCRATCH_PREFIX}"*|"${ROOT_POSIX}/${SCRATCH_PREFIX}"*)
      rm -rf "${stale}" && swept=$((swept + 1))
      ;;
  esac
done < <(mirror_dirs)
printf 'scratch: %s (stale mirrors swept at start: %s)\n' "${SCRATCH_PARENT}" "${swept}"

TEMPS=""
MIRROR=""
MIRRORS=0
cleanup() {
  local d
  for d in ${TEMPS}; do
    case "${d}" in
      "${SCRATCH_PARENT}/${SCRATCH_PREFIX}"*|"${ROOT_POSIX}/${SCRATCH_PREFIX}"*) [ -d "${d}" ] && rm -rf "${d}" ;;
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
  dir="$(mktemp -d -p "${SCRATCH_PARENT}" "${SCRATCH_PREFIX}XXXXXX")" || {
    printf 'FAIL: mktemp -d failed under %s\n' "${SCRATCH_PARENT}" >&2; exit 1; }
  case "${dir}" in
    /*) : ;;
    *) dir="${SCRATCH_PARENT}/${dir}" ;;
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
LIST_RC="${RC}"
LIST_OUT="${OUT}"
if [ "${LIST_RC}" -eq 0 ] && printf '%s\n' "${LIST_OUT}" | grep -q 'forbidden call patterns' \
   && printf '%s\n' "${LIST_OUT}" | grep -q 'isolation allow-list'; then
  pass "--list exits 0 and prints the forbidden call table and the isolation allow-list"
else
  bad "--list exited ${LIST_RC} without printing the expected tables"
  show "${LIST_OUT}"
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
# The guard's declared pattern set, derived from its own --list output rather
# than copied into this file. Everything downstream - the structural audit in
# case 5c and the "every pattern still bites" sweep in case 7 - reads this.
# ---------------------------------------------------------------------------
GUARD_BASE_NAMES=()
while IFS= read -r name; do
  [ -n "${name}" ] || continue
  GUARD_BASE_NAMES+=("${name}")
done < <(printf '%s\n' "${LIST_OUT}" \
         | awk '/^forbidden call patterns/ { inlist=1; next } /^isolation allow-list/ { inlist=0 } inlist' \
         | sed -E 's/^[[:space:]]+//; s/\[\[:space:\]\]\*\\\($//' \
         | grep -E '^[A-Za-z][A-Za-z0-9_]*$' \
         | LC_ALL=C sort -u)
printf 'declared patterns: %s\n' "${GUARD_BASE_NAMES[*]}"

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

# 5c. The structural half of the read-only rule. The guard's 15 text patterns are
#     deliberately a subset: the collection-shaped and set*-shaped mutating SDK
#     members (policy.add, itemType.update, itemType.setName) are excluded from
#     the stub signatures instead, so such a call does not compile against
#     tests/ibm-stubs at all. They cannot be tested by compiling a bad call here -
#     this suite must compile - so the auditable assertion is on the pinned
#     expectation and the stub sources themselves.
#
#     The mutating names are derived from the repository's OWN pinned evidence,
#     harness/IBM_CM87_SDK_API_SURFACE.md section 10 ("Mutating methods (for the
#     read-only source guard)"), never from a list invented here. That keeps this
#     assertion honest: it fails if a mutating member ever reaches a stub, and it
#     cannot be satisfied by weakening either side of the derivation.
API_SURFACE='harness/IBM_CM87_SDK_API_SURFACE.md'
SIGNATURES='tests/ibm-stubs/EXPECTED_SIGNATURES.txt'
[ -r "${ROOT}/${API_SURFACE}" ] || bad "missing ${API_SURFACE}; the mutating-member derivation has no source"
[ -r "${ROOT}/${SIGNATURES}" ] || bad "missing ${SIGNATURES}; the stub-signature audit has no subject"

mut_names=()
while IFS= read -r name; do
  [ -n "${name}" ] || continue
  mut_names+=("${name}")
done < <(awk '/^## 10\. Mutating methods/ { insec=1 } /^## 11\./ { insec=0 } insec' "${ROOT}/${API_SURFACE}" 2>/dev/null \
         | grep -o '`[A-Za-z][A-Za-z0-9_]*(' \
         | sed -E 's/^`//; s/\($//' | LC_ALL=C sort -u)

if [ "${#mut_names[@]}" -ge 50 ]; then
  pass "the mutating-member derivation is non-trivial: ${#mut_names[@]} distinct mutating names in ${API_SURFACE##*/} section 10"
else
  bad "section 10 of ${API_SURFACE##*/} yielded only ${#mut_names[@]} mutating names; the derivation found nothing to audit"
fi

# Provenance note, stated rather than faked: the guard's extra names (assign,
# unassign, backfill, migrate, reorg, recreate, makeActive, makeInactive) come from
# the goal file's prose list in harness/GOAL_02_IBM_CM_RETENTION.md lines 263-268
# ("commit", "update", "add/del", "assign/remove policies",
# "delete/backfill/migration APIs"), which is not machine-extractable, and the doc
# writes Java's own methods in backticks in the same style as the SDK's, so a
# derived "every guard name is documented" check cannot be made honest - it either
# passes vacuously or needs a hand-written list that would rot. What IS checkable,
# and is the exact shape of the regression that disabled this guard, is whether the
# table has become generic: a name that Java itself owns can never be a refusal.
JDK_GENERIC=' add remove update delete del set put get clear size contains isEmpty iterator addAll removeAll retainAll sort stream forEach equals hashCode toString clone copyOf of valueOf empty singleton apply accept test close destroy disconnect '
generic_in_guard=""
for n in "${GUARD_BASE_NAMES[@]}"; do
  case "${JDK_GENERIC}" in
    *" ${n} "*) generic_in_guard="${generic_in_guard} ${n}" ;;
  esac
done
if [ -z "${generic_in_guard}" ]; then
  pass "no declared pattern is a name Java itself owns; the table is IBM-specific, not generic"
else
  bad "the table declares generic name(s) that java.util or Object also owns:${generic_in_guard} - that is the false positive that disabled this guard"
fi

# The stub teardown contract is explicit and narrow: the real SDK has no close(),
# and teardown is disconnect() then destroy(). An audit that treated those three
# as mutating would fail on correct stubs, so they are named here as the ONLY
# write-shaped members a stub may declare.
TEARDOWN_ALLOWED=' destroy disconnect close '

# The pinned signature expectation must hold no mutating member other than those.
stub_leaks=""
sig_map="${T5B}/sig-audit.txt"
: > "${sig_map}"
while IFS= read -r line; do
  case "${line}" in
    *'('*')'*)
      case "${line}" in
        *'class '*|*'interface '*|*'Compiled from'*) continue ;;
      esac
      before="${line%%(*}"
      tok="${before##* }"
      tok="${tok##*.}"
      printf '%s\n' "${tok}" >> "${sig_map}"
      ;;
  esac
done < "${ROOT}/${SIGNATURES}"
for n in "${mut_names[@]}"; do
  grep -q -x -F "${n}" "${sig_map}" || continue
  case "${TEARDOWN_ALLOWED}" in
    *" ${n} "*) continue ;;
  esac
  stub_leaks="${stub_leaks} ${n}"
done
if [ -z "${stub_leaks}" ]; then
  pass "${SIGNATURES##*/} declares no mutating member beyond the documented teardown (${#mut_names[@]} names audited)"
else
  bad "${SIGNATURES##*/} declares mutating member(s) that must not be there:${stub_leaks}"
fi

# The real SDK has no close(); if one ever appears as a DECLARATION in the stubs,
# the teardown contract itself changed and the allowance above must be revisited.
# Comment lines are skipped: the pinned header states the rule in words.
if grep -v '^[[:space:]]*#' "${ROOT}/${SIGNATURES}" | grep -q -E '(^|[[:space:]])close[[:space:]]*\('; then
  bad "${SIGNATURES##*/} declares close(); the SDK has no close() and teardown is disconnect()+destroy()"
else
  pass "${SIGNATURES##*/} declares no close(), matching the documented teardown contract"
fi

# And the stubs' own SOURCES must hold no mutating member either, so a stub cannot
# grow one without the pinned file being regenerated. The match is a declaration
# shape - a modifier or return type before the name - so the javadoc in
# DKPolicyMgmtICM.java that documents the deliberately absent add/update/del
# members is not mistaken for a declaration, and the documented teardown members
# are allowed rather than reported.
stub_source_leaks=""
stub_source_count=0
while IFS= read -r f; do
  stub_source_count=$((stub_source_count + 1))
  for n in "${mut_names[@]}"; do
    case "${TEARDOWN_ALLOWED}" in
      *" ${n} "*) continue ;;
    esac
    grep -q -E "(^|[[:space:]])((public|protected|private|static|final|abstract|synchronized|native|default)[[:space:]]+)*[A-Za-z_][A-Za-z0-9_.]*[[:space:]]*(\[\])?[[:space:]]+${n}[[:space:]]*\(|^${n}[[:space:]]*\(" "${f}" \
      && stub_source_leaks="${stub_source_leaks} ${f##*/}:${n}"
  done
done < <(find "${ROOT}/tests/ibm-stubs" -type f -name '*.java' 2>/dev/null | LC_ALL=C sort)
if [ "${stub_source_count}" -eq 0 ]; then
  bad "tests/ibm-stubs holds no .java source; the structural layer cannot be audited and would silently vanish"
elif [ -z "${stub_source_leaks}" ]; then
  pass "${stub_source_count} stub source(s) declare no mutating member"
else
  bad "stub source(s) declare mutating member(s):${stub_source_leaks}"
fi

# ---------------------------------------------------------------------------
# Case 6 (regression, captain-requested): ordinary java.util / wrapper calls on
# the adapter's OWN locals must never be refused. This is the assertion that
# stops the next person from "fixing" a false positive by deleting the guard.
#
# It exists because a receiver-agnostic `add`/`remove`/`update`/`delete`/`del`/`set`
# pattern once refused IbmCmSessionPool.java's own `liveSessions.remove(session)`
# and `dead.add(session)` - a guard that cannot pass is a guard that gets disabled.
# ---------------------------------------------------------------------------
new_mirror
T6="${MIRROR}"
rel="$(probe_rel CmdsLocalCollections.java)"
printf '%s\n' \
  'package com.mraibo.cminsight.ibm.internal;' \
  '' \
  'import java.util.ArrayList;' \
  'import java.util.List;' \
  'import java.util.Map;' \
  'import java.util.concurrent.ConcurrentHashMap;' \
  'import java.util.concurrent.atomic.AtomicBoolean;' \
  'import java.util.concurrent.atomic.AtomicLong;' \
  '' \
  '/** Scratch fixture: adapter-local java.util bookkeeping, none of it an SDK call. */' \
  'final class CmdsLocalCollections {' \
  '  private final List<String> names = new ArrayList<>();' \
  '  private final Map<String, Long> liveSessions = new ConcurrentHashMap<>();' \
  '  private final AtomicBoolean closed = new AtomicBoolean(false);' \
  '  private final AtomicLong leases = new AtomicLong();' \
  '' \
  '  void bookkeeping() {' \
  '    names.add("itemType");' \
  '    names.remove(0);' \
  '    names.clear();' \
  '    names.set(0, "retention");' \
  '    liveSessions.put("session", 1L);' \
  '    liveSessions.remove("session");' \
  '    closed.set(true);' \
  '    leases.set(2L);' \
  '  }' \
  '}' > "${T6}/${rel}"
TREE="${T6}"
run_guard
if [ "${RC}" -eq 0 ]; then
  pass "adapter-local collections (list.add/remove/clear/set, map.put/remove, atomic.set) are NOT refused"
else
  bad "the guard refused adapter-local bookkeeping: ${RC} - a false positive would disable the guard"
  show "$(printf '%s\n' "${OUT}" | grep -F 'ERROR:' | tr '\n' ' ')"
fi
if printf '%s\n' "${OUT}" | grep -q -F 'ERROR:'; then
  bad "the local-collection fixture produced a refusal line"
else
  pass "the local-collection fixture produced no refusal line at all"
fi

# ---------------------------------------------------------------------------
# Case 7 (regression, captain-requested): every pattern the guard still declares
# must still bite. This is the second half of the pair - case 6 forbids
# over-broadening, this forbids "narrowing" the guard into uselessness.
#
# GUARD_BASE_NAMES was derived from the guard's own --list output above, so the
# expected set is registered by the lead's edit to tests/shell/ibm_guard.sh and
# then verified mechanically here instead of being copied into this file by hand.
# ---------------------------------------------------------------------------
if [ "${#GUARD_BASE_NAMES[@]}" -eq 15 ]; then
  pass "the guard declares exactly ${#GUARD_BASE_NAMES[@]} unambiguous mutating patterns"
else
  bad "the guard declares ${#GUARD_BASE_NAMES[@]} patterns, not 15; the read-only rule changed shape"
  show "${GUARD_BASE_NAMES[*]}"
fi

if [ "${#GUARD_BASE_NAMES[@]}" -gt 0 ]; then
  for n in "${GUARD_BASE_NAMES[@]}"; do
    case "${n}" in
      commit|rollback|checkIn|checkOut|backfill|migrate|moveObject|changePassword|makeActive|makeInactive|reorg|recreate|clearCache|assign|unassign) ;;
      *) bad "the guard declares '${n}', which is not one of the 15 agreed unambiguous SDK mutations" ;;
    esac
  done
  pass "every declared pattern is one of the 15 agreed IBM-specific mutation names"
else
  bad "the guard declared no forbidden pattern; --list may have been weakened"
fi

# 7b. A single pass must name every one of them.
new_mirror
T7="${MIRROR}"
rel="$(probe_rel CmdsAllPatterns.java)"
printf '%s\n' \
  'package com.mraibo.cminsight.ibm.internal;' \
  '' \
  '/** Scratch fixture: one call per forbidden pattern, each on its own line. */' \
  'final class CmdsAllPatterns {' \
  '  void all15() {' > "${T7}/${rel}"
for n in "${GUARD_BASE_NAMES[@]}"; do
  printf '    handle.%s();\n' "${n}" >> "${T7}/${rel}"
done
printf '%s\n' '  }' '}' >> "${T7}/${rel}"
TREE="${T7}"
run_guard
all15_rc="${RC}"
all15_out="${OUT}"
if [ "${all15_rc}" -ne 0 ]; then
  pass "a file calling all ${#GUARD_BASE_NAMES[@]} declared patterns is refused (exit ${all15_rc})"
else
  bad "the guard accepted a file calling every declared pattern"
fi
for n in "${GUARD_BASE_NAMES[@]}"; do
  printf '%s\n' "${all15_out}" | grep -q -F "handle.${n}();" || bad "planted 'handle.${n}();' was NOT refused, yet the guard still lists ${n} as forbidden"
done
pass "all ${#GUARD_BASE_NAMES[@]} planted patterns were individually refused and named"

# 7c. Names that only RESEMBLE a forbidden call must not be refused: the patterns
#     are anchored on the call parenthesis on purpose.
new_mirror
T7C="${MIRROR}"
rel="$(probe_rel CmdsResembling.java)"
printf '%s\n' \
  'package com.mraibo.cminsight.ibm.internal;' \
  '' \
  '/** Scratch fixture: getters whose names merely resemble a forbidden call. */' \
  'final class CmdsResembling {' \
  '  int resembling() {' \
  '    return handle.commitCount() + handle.deleteExpiredItemsMaximumRows();' \
  '  }' \
  '}' > "${T7C}/${rel}"
TREE="${T7C}"
run_guard
if [ "${RC}" -eq 0 ]; then
  pass "getters that merely resemble a forbidden name (commitCount, deleteExpiredItemsMaximumRows, ...) are NOT refused"
else
  bad "the guard refused getters whose names only resemble a forbidden call (exit ${RC}): the parenthesis anchor regressed"
  show "$(printf '%s\n' "${OUT}" | grep -F 'ERROR:' | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------------
# Case 8: the guard refuses to pass by scanning nothing.
# ---------------------------------------------------------------------------
# 8a. src/main/java absent: nothing to scan, which is a layout failure, not a clean result.
new_mirror
T8A="${MIRROR}"
mv "${T8A}/src/main/java" "${T8A}/core-backup" || { printf 'FAIL: cannot move src/main/java aside\n' >&2; exit 1; }
TREE="${T8A}"
run_guard
assert_refused "a missing src/main/java" nonzero "${T8A}/src/main/java" 'nothing to scan'
if [ -d "${T8A}/core-backup" ]; then
  mv "${T8A}/core-backup" "${T8A}/src/main/java"
fi

# 8b. src/ibm/java present but holding no .java file: the read-only guard would scan nothing.
new_mirror
T8B="${MIRROR}"
mv "${T8B}/src/ibm/java" "${T8B}/ibm-backup" || { printf 'FAIL: cannot move src/ibm/java aside\n' >&2; exit 1; }
mkdir -p "${T8B}/src/ibm/java/com/mraibo/cminsight/ibm/internal" || { printf 'FAIL: cannot create the empty IBM tree\n' >&2; exit 1; }
empty_count="$(find "${T8B}/src/ibm/java" -name '*.java' -print | wc -l | tr -d ' ')"
TREE="${T8B}"
run_guard
if [ "${empty_count}" -ne 0 ]; then
  bad "the empty src/ibm/java fixture held ${empty_count} .java file(s); the case proves nothing"
else
  assert_refused "an empty src/ibm/java" nonzero "${T8B}/src/ibm/java" 'contains no .java file'
fi
if [ -d "${T8B}/ibm-backup" ]; then
  rm -rf "${T8B}/src/ibm/java"
  mv "${T8B}/ibm-backup" "${T8B}/src/ibm/java"
fi

# ---------------------------------------------------------------------------
# Case 9: no side effects. Removing every scratch tree here - before the real
# tree is checked again - proves the trap removes what the test created, and the
# real acceptance run proves no plant reached the repository.
# ---------------------------------------------------------------------------
count="${MIRRORS}"
# Twelve mirrors are expected (1 control, 3 single read-only plants, 1
# multi-plant, 2 isolation plants, 1 local-collection control, 1 all-patterns
# plant, 1 resembling-name control, 2 scan-nothing cases). Demand a clear lower
# bound so an edit that silently drops a mutation case is caught here.
[ "${count}" -ge 10 ] || bad "expected the mutation cases to create several mirror trees, saw ${count}"
cleanup
leftover=0
for d in ${TEMPS}; do [ -d "${d}" ] && leftover=$((leftover + 1)); done
if [ "${leftover}" -eq 0 ]; then
  pass "all ${count} scratch mirror tree(s) were removed by the trap"
else
  bad "${leftover} of ${count} scratch mirror tree(s) survived cleanup"
fi
TEMPS=""

# A mirror must not survive anywhere - not in the system temp directory and not in
# the working tree. The second location is the one that matters for a killed run:
# residue there is untracked junk a later `git add -A` would sweep into a commit.
residual_tmp=0
residual_repo=0
while IFS= read -r d; do
  [ -n "${d}" ] || continue
  case "${d}" in
    "${SCRATCH_PARENT}/${SCRATCH_PREFIX}"*) residual_tmp=$((residual_tmp + 1)) ;;
  esac
  case "${d}" in
    "${ROOT_POSIX}/${SCRATCH_PREFIX}"*) residual_repo=$((residual_repo + 1)) ;;
  esac
done < <(mirror_dirs)
if [ "${residual_tmp}" -eq 0 ] && [ "${residual_repo}" -eq 0 ]; then
  pass "no ${SCRATCH_PREFIX}* directory is left in ${SCRATCH_PARENT} or in the working tree"
else
  bad "${residual_tmp} scratch dir(s) left in ${SCRATCH_PARENT}, ${residual_repo} left in the working tree"
  show "$(mirror_dirs | tr '\n' ' ')"
fi

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
