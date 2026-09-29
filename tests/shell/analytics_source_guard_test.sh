#!/usr/bin/env bash
#
# CM Insight - Goal 03 section 7 regression test for the analytics read-only guard.
#
#   ./tests/shell/analytics_source_guard_test.sh
#
# Owner: the test suite. The implementation under test is the committed helper
# tests/shell/analytics_guard.sh, which is deliberately NOT named *_test.sh so
# that tests/shell/run.sh does not discover it directly. This file owns every
# assertion about that guard.
#
# A guard that only ever exits 0 is worthless, so a green acceptance run is NOT
# evidence. Every mutation is planted in a throw-away mirror tree under
# mktemp -d - the real src/ tree is never touched - and the guard is pointed at
# the mirror with its own --root option. Each mutation case gets its OWN fresh
# mirror, so no earlier plant can explain, hide or inflate a later refusal.
#
# Cases:
#   1. interface          --help exits 0, --list prints the tables, an unknown
#                         argument is exit 2 (so 1 stays reserved for a violation)
#   2. acceptance         the guard holds on the committed tree (exit 0) and its
#                         report PROVES it scanned files, so a pass is not silence
#   3. missing tree       the analytics tree removed -> non-zero, names the tree.
#                         A rename must never disable the rule silently
#   4. empty tree         the tree present with no .java file -> non-zero
#   5. write calls        planted executeUpdate, executeLargeUpdate, addBatch,
#                         executeBatch, commit, rollback and prepareCall are each
#                         refused AND named, one plant per fresh mirror
#   6. statement literals planted "DELETE FROM ...", "UPDATE ... SET ...",
#                         "MERGE INTO ...", "TRUNCATE TABLE ...", "DROP TABLE ..."
#                         and "GRANT SELECT ..." literals are each refused, and
#                         the JDBC `{call ...}` escape is refused too
#   7. no false positives O ordinary java.util / wrapper operations, a getter whose
#                         name merely RESEMBLES a forbidden call (updateCount,
#                         commitCount, rollbackCount), an English sentence that
#                         mentions commit, and ordinary SELECT literals are NOT
#                         refused. This is the assertion that stops the next
#                         person from "fixing" a false positive by deleting the
#                         guard - and it is also what keeps the SQL-keyword rule
#                         anchored on the opening quote
#   8. every pattern bites every call pattern the guard still DECLARES must still
#                         be refused, read from the guard's own --list output, so
#                         adding a pattern to the guard registers it here
#                         mechanically instead of by hand
#   9. no side effects    every scratch tree is removed and the real tree still
#                         passes, so no plant reached the repository
#
# Message prefixes: "ok:" pass, "FAIL:" fail, "cmd:" executed command + exit code.
# Exit codes: 0 every case passed, 1 at least one case failed.

set -u

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${SELF}")" && pwd)" || { printf 'ERROR: cannot locate %s\n' "${SELF}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)" || { printf 'ERROR: cannot resolve the repository root\n' >&2; exit 2; }

GUARD="${ROOT}/tests/shell/analytics_guard.sh"
PRIMARY_REL='src/main/java/com/mraibo/cminsight/db'
SECONDARY_REL='src/main/java/com/mraibo/cminsight/statistics'
SCRATCH_PREFIX='analytics-guard-test.'

ROOT_POSIX="$(printf '%s' "${ROOT}" | sed -E 's#^([A-Za-z]):[\\/]#/\L\1/#; s#\\#/#g')"
[ -d "${ROOT_POSIX}" ] || { printf 'FAIL: cannot address the repository root from bash: %s\n' "${ROOT}" >&2; exit 1; }

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

mirror_dirs() {
  local parent d
  for parent in "${SCRATCH_PARENT}" "${ROOT_POSIX}"; do
    for d in "${parent}"/${SCRATCH_PREFIX}*; do
      [ -d "${d}" ] || continue
      printf '%s\n' "${d}"
    done
  done
}

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

[ -f "${GUARD}" ] || { printf 'FAIL: missing %s; the guard under test does not exist\n' "${GUARD}" >&2; exit 1; }
[ -r "${GUARD}" ] || { printf 'FAIL: %s is not readable\n' "${GUARD}" >&2; exit 1; }

printf 'sha256: analytics_guard.sh = %s\n' "$(sha256sum < "${GUARD}" | cut -d' ' -f1)"

grep -q 'set -euo pipefail' "${GUARD}" || bad "analytics_guard.sh lost its 'set -euo pipefail' prologue"
grep -q 'FORBIDDEN_CALL_PATTERNS=' "${GUARD}" || bad "analytics_guard.sh no longer carries a forbidden-call table"
grep -q "${PRIMARY_REL}" "${GUARD}" || bad "analytics_guard.sh no longer names the analytics JDBC source tree ${PRIMARY_REL}"

# ---------------------------------------------------------------------------
# Running the guard. TREE selects the tree the guard must treat as its root.
# ---------------------------------------------------------------------------
TREE="${ROOT}"
RC=0
OUT=''

run_guard() {
  printf 'cmd: (cd %s && ./tests/shell/analytics_guard.sh --root %s %s)\n' "${TREE}" "${TREE}" "${*:-}" >&2
  OUT="$( cd "${TREE}" && bash "${GUARD}" --root "${TREE}" "$@" 2>&1 )"
  RC=$?
  printf 'cmd: exit=%s\n' "${RC}" >&2
}

# ---------------------------------------------------------------------------
# Mirror trees: a scratch repository with the two scanned trees laid out.
# ---------------------------------------------------------------------------
db_file() {
  printf '%s/%s/Probe.java' "$1" "${PRIMARY_REL}"
}

new_mirror() {
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
  mkdir -p "${dir}/${PRIMARY_REL}" "${dir}/${SECONDARY_REL}" || {
    printf 'FAIL: cannot lay out the scratch tree %s\n' "${dir}" >&2; exit 1; }
  printf '%s\n' \
    'package com.mraibo.cminsight.db;' \
    '' \
    '/** Scratch fixture: a clean analytics JDBC source file. SELECT only. */' \
    'final class Probe {' \
    '  String currentDate() { return "SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1"; }' \
    '}' > "$(db_file "${dir}")"
  printf '%s\n' \
    'package com.mraibo.cminsight.statistics;' \
    '' \
    '/** Scratch fixture: a clean statistics source file. */' \
    'final class StatsProbe {' \
    '  private StatsProbe() { }' \
    '}' > "${dir}/${SECONDARY_REL}/StatsProbe.java"
  MIRROR="${dir}"
}

plant() {
  printf '  %s\n' "$3" >> "$1/$2" || { printf 'FAIL: cannot plant into %s\n' "$2" >&2; exit 1; }
}

# assert_refused <label> <expected exit (code or "nonzero")> <path the refusal must name> <text the refusal must contain>
assert_refused() {
  local label="$1" want_rc="$2" want_path="$3" want_text="$4"
  local rc="${RC}" out="${OUT}"
  if [ "${want_rc}" = nonzero ]; then
    if [ "${rc}" -eq 0 ]; then
      bad "${label}: the guard exited 0; it did not bite"
      show "$(printf '%s\n' "${out}" | tr '\n' ' ')"
      return
    fi
  elif [ "${rc}" -ne "${want_rc}" ]; then
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
  pass "${label}: refused with exit ${rc}, naming $(basename -- "${want_path}")"
  show "$(printf '%s\n' "${out}" | grep -F "${want_text}" | head -n 1)"
}

# assert_accepted <label>
assert_accepted() {
  local label="$1"
  if [ "${RC}" -eq 0 ]; then
    pass "${label}: accepted (exit 0)"
  else
    bad "${label}: the guard refused it (exit ${RC})"
    show "$(printf '%s\n' "${OUT}" | grep -F 'ERROR:' | tr '\n' ' ')"
  fi
}

# ---------------------------------------------------------------------------
# Case 1: the guard's own documented interface.
# ---------------------------------------------------------------------------
TREE="${ROOT}"
run_guard --help
if [ "${RC}" -eq 0 ] && printf '%s\n' "${OUT}" | grep -q 'Usage:'; then
  pass "--help exits 0 and prints usage"
else
  bad "--help exited ${RC} without printing usage"
fi

run_guard --list
LIST_RC="${RC}"
LIST_OUT="${OUT}"
if [ "${LIST_RC}" -eq 0 ] && printf '%s\n' "${LIST_OUT}" | grep -q 'forbidden JDBC write/control calls' \
   && printf '%s\n' "${LIST_OUT}" | grep -q 'forbidden statement literals'; then
  pass "--list exits 0 and prints the scanned trees and the forbidden patterns"
else
  bad "--list exited ${LIST_RC} without printing the expected tables"
  show "${LIST_OUT}"
fi

run_guard --definitely-not-a-flag
if [ "${RC}" -eq 2 ] && printf '%s\n' "${OUT}" | grep -q 'unknown argument'; then
  pass "an unknown argument is exit 2 (usage), keeping 1 reserved for a violated rule"
else
  bad "an unknown argument produced exit ${RC} instead of 2"
fi

# The declared call patterns, read from the guard's own --list output rather than
# copied into this file, so case 8 below registers a newly added pattern
# mechanically.
DECLARED_CALL_PATTERNS=()
while IFS= read -r pattern; do
  [ -n "${pattern}" ] || continue
  DECLARED_CALL_PATTERNS+=("${pattern}")
done < <(printf '%s\n' "${LIST_OUT}" \
         | awk '/^forbidden JDBC write\/control calls/ { inlist=1; next } /^forbidden statement literals/ { inlist=0 } inlist' \
         | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' \
         | grep -E '^[A-Za-z]' | LC_ALL=C sort -u)
printf 'declared call patterns: %s\n' "${DECLARED_CALL_PATTERNS[*]}"

# ---------------------------------------------------------------------------
# Case 2: acceptance - the guard holds on the committed tree, and it PROVES it
# scanned something. An exit 0 with no file count would be indistinguishable from
# a guard that scanned nothing.
# ---------------------------------------------------------------------------
TREE="${ROOT}"
run_guard
if [ "${RC}" -eq 0 ]; then
  pass "acceptance: the guard exits 0 on the committed tree"
  if printf '%s\n' "${OUT}" | grep -qE 'OK: the analytics source tree was scanned \([1-9][0-9]* \.java'; then
    pass "acceptance: the pass reports a non-zero scanned-file count, so it did not pass by scanning nothing"
    show "$(printf '%s\n' "${OUT}" | grep -F 'was scanned' | head -n 1)"
  else
    bad "the acceptance pass does not report a non-zero scanned-file count"
    show "${OUT}"
  fi
else
  bad "acceptance: the guard exited ${RC} on the committed tree"
  printf '%s\n' "${OUT}" | grep -F 'ERROR:' | sed 's/^/     | /' >&2
fi

# ---------------------------------------------------------------------------
# Case 3: a missing analytics tree is a LAYOUT failure, never a clean result.
# ---------------------------------------------------------------------------
new_mirror
T3="${MIRROR}"
rm -rf "${T3}/${PRIMARY_REL}"
TREE="${T3}"
run_guard
assert_refused "a missing ${PRIMARY_REL}" nonzero "${T3}/${PRIMARY_REL}" 'nothing to scan'

# ---------------------------------------------------------------------------
# Case 4: the tree present but holding no .java file.
# ---------------------------------------------------------------------------
new_mirror
T4="${MIRROR}"
rm -f "$(db_file "${T4}")"
TREE="${T4}"
run_guard
assert_refused "an empty ${PRIMARY_REL}" nonzero "${T4}/${PRIMARY_REL}" 'contains no .java file'

# ---------------------------------------------------------------------------
# Case 5: planted JDBC write/control calls are refused and named.
# ---------------------------------------------------------------------------
# The `@@` separator is used instead of `:` because the declared patterns contain
# a `:` character inside `[[:space:]]`, and a pattern/statement pair split on the
# wrong colon would silently plant a pattern instead of a call.
planted_calls=(
  'executeUpdate[[:space:]]*\(@@void write() throws Exception { statement.executeUpdate("DELETE FROM T"); }'
  'executeLargeUpdate[[:space:]]*\(@@void writeLarge() throws Exception { statement.executeLargeUpdate("DELETE FROM T"); }'
  'addBatch[[:space:]]*\(@@void batch() throws Exception { statement.addBatch("DELETE FROM T"); }'
  'executeBatch[[:space:]]*\(@@void runBatch() throws Exception { statement.executeBatch(); }'
  'commit[[:space:]]*\(@@void finish() throws Exception { connection.commit(); }'
  'rollback[[:space:]]*\(@@void undo() throws Exception { connection.rollback(); }'
  'prepareCall[[:space:]]*\(@@void procedure() throws Exception { connection.prepareCall("{call DO_SOMETHING()}"); }'
)
plant_index=0
for entry in "${planted_calls[@]}"; do
  plant_index=$((plant_index + 1))
  statement="${entry#*@@}"
  new_mirror
  T="${MIRROR}"
  rel="${PRIMARY_REL}/Planted${plant_index}.java"
  plant "${T}" "${rel}" "${statement}"
  TREE="${T}"
  run_guard
  assert_refused "planted '${statement}'" nonzero "${T}/${rel}" 'Goal 03 analytics read-only violation'
done

# ---------------------------------------------------------------------------
# Case 6: planted statement literals beginning with a write/control SQL keyword,
# plus the JDBC CALL escape.
# ---------------------------------------------------------------------------
planted_literals=(
  'DELETE':'String sql() { return "DELETE FROM ICMUT00001001"; }'
  'UPDATE':'String sql() { return "UPDATE ICMUT00001001 SET ITEMID = 1"; }'
  'MERGE':'String sql() { return "MERGE INTO ICMUT00001001 USING X ON 1=1"; }'
  'TRUNCATE':'String sql() { return "TRUNCATE TABLE ICMUT00001001 IMMEDIATE"; }'
  'DROP':'String sql() { return "DROP TABLE ICMUT00001001"; }'
  'GRANT':'String sql() { return "GRANT SELECT ON ICMUT00001001 TO PUBLIC"; }'
)
literal_index=0
for entry in "${planted_literals[@]}"; do
  literal_index=$((literal_index + 1))
  statement="${entry#*:}"
  new_mirror
  T="${MIRROR}"
  rel="${PRIMARY_REL}/Literal${literal_index}.java"
  plant "${T}" "${rel}" "${statement}"
  TREE="${T}"
  run_guard
  assert_refused "planted statement literal '${entry%%:*}'" nonzero "${T}/${rel}" 'Goal 03 analytics read-only violation'
done

new_mirror
T6B="${MIRROR}"
rel="${PRIMARY_REL}/CallEscape.java"
plant "${T6B}" "${rel}" 'String sql() { return "{call DO_SOMETHING(1)}"; }'
TREE="${T6B}"
run_guard
assert_refused "planted JDBC CALL escape '{call ...}'" nonzero "${T6B}/${rel}" 'stored-procedure CALL escape'

# A text block that begins with the keyword on the same line is refused too.
new_mirror
T6C="${MIRROR}"
rel="${PRIMARY_REL}/TextBlock.java"
printf '  String sql() { return """DELETE FROM ICMUT00001001"""; }\n' >> "${T6C}/${rel}"
TREE="${T6C}"
run_guard
assert_refused "planted text block beginning with DELETE" nonzero "${T6C}/${rel}" 'Goal 03 analytics read-only violation'

# The plant is really in the file the guard scanned, so the refusals above cannot
# have come from an unscanned tree.
if grep -q -F 'DELETE FROM ICMUT00001001' "${T6C}/${rel}"; then
  pass "positive control: the last plant is present in a file the guard scanned"
else
  bad "the plant is missing from ${T6C}/${rel}; the refusal evidence would be meaningless"
fi

# ---------------------------------------------------------------------------
# Case 7: no false positives. Ordinary java.util and wrapper operations, getters
# whose NAME merely resembles a forbidden call, an English sentence that mentions
# commit, and ordinary SELECT-only literals must all be accepted. A guard that
# cannot pass is a guard that gets disabled - and this is also what keeps the
# SQL-keyword rule anchored on the opening quote.
# ---------------------------------------------------------------------------
new_mirror
T7="${MIRROR}"
rel="${PRIMARY_REL}/LocalOnly.java"
printf '%s\n' \
  'package com.mraibo.cminsight.db;' \
  '' \
  'import java.util.ArrayList;' \
  'import java.util.List;' \
  'import java.util.Map;' \
  'import java.util.concurrent.atomic.AtomicBoolean;' \
  'import java.util.concurrent.atomic.AtomicLong;' \
  '' \
  '/**' \
  ' * A commit is never issued here; rollback likewise. The words appear in prose on' \
  ' * purpose, so the guard must be anchored on a call parenthesis.' \
  ' */' \
  'final class LocalOnly {' \
  '  private final List<String> names = new ArrayList<>();' \
  '  private final Map<String, Long> counters = new java.util.HashMap<>();' \
  '  private final AtomicBoolean closed = new AtomicBoolean(false);' \
  '  private final AtomicLong leases = new AtomicLong();' \
  '' \
  '  int resembling() { return handle.updateCount() + handle.commitCount() + handle.rollbackCount(); }' \
  '' \
  '  String selectOnly() { return "SELECT COUNT(*) FROM ICMUT00001001 WHERE ITEMID = ?"; }' \
  '' \
  '  String unionSql() {' \
  '    return "SELECT ItemID FROM ICMUT00001001 UNION SELECT ItemID FROM ICMUT00001002";' \
  '  }' \
  '' \
  '  void bookkeeping() {' \
  '    names.add("itemType");' \
  '    names.remove(0);' \
  '    names.clear();' \
  '    counters.put("reads", 1L);' \
  '    counters.remove("reads");' \
  '    closed.set(true);' \
  '    leases.set(2L);' \
  '  }' \
  '}' > "${T7}/${rel}"
TREE="${T7}"
run_guard
assert_accepted "adapter-local collections, updateCount()/commitCount()/rollbackCount() and SELECT-only literals"
if printf '%s\n' "${OUT}" | grep -q -F 'ERROR:'; then
  bad "the no-false-positive fixture produced a refusal line"
  show "$(printf '%s\n' "${OUT}" | grep -F 'ERROR:' | tr '\n' ' ')"
else
  pass "the no-false-positive fixture produced no refusal line at all"
fi

# The secondary tree is scanned too: a write call planted in statistics/ must be
# refused there, not only in db/.
new_mirror
T7B="${MIRROR}"
rel="${SECONDARY_REL}/PlantedScan.java"
plant "${T7B}" "${rel}" 'void write() throws Exception { connection.commit(); }'
TREE="${T7B}"
run_guard
assert_refused "a planted write call in ${SECONDARY_REL}" nonzero "${T7B}/${rel}" 'Goal 03 analytics read-only violation'

# ---------------------------------------------------------------------------
# Case 8: every call pattern the guard still declares must still bite. The
# expected set comes from the guard's own --list output, so a pattern added there
# is registered here mechanically instead of by hand.
# ---------------------------------------------------------------------------
if [ "${#DECLARED_CALL_PATTERNS[@]}" -ge 8 ]; then
  pass "the guard declares ${#DECLARED_CALL_PATTERNS[@]} JDBC write/control call patterns"
else
  bad "the guard declares only ${#DECLARED_CALL_PATTERNS[@]} call patterns; the read-only rule was narrowed"
fi

new_mirror
T8="${MIRROR}"
rel="${PRIMARY_REL}/AllDeclaredPatterns.java"
printf '%s\n' \
  'package com.mraibo.cminsight.db;' \
  '' \
  '/** Scratch fixture: one call per declared pattern, each on its own line. */' \
  'final class AllDeclaredPatterns {' \
  '  void everyDeclaredPattern() throws Exception {' > "${T8}/${rel}"
for pattern in "${DECLARED_CALL_PATTERNS[@]}"; do
  base="${pattern%%[[]*}"
  printf '    statement.%s();\n' "${base}" >> "${T8}/${rel}"
done
printf '%s\n' '  }' '}' >> "${T8}/${rel}"
TREE="${T8}"
run_guard
if [ "${RC}" -ne 0 ]; then
  pass "a file calling every declared pattern is refused (exit ${RC})"
else
  bad "the guard accepted a file calling every declared pattern"
fi
missing_patterns=0
for pattern in "${DECLARED_CALL_PATTERNS[@]}"; do
  base="${pattern%%[[]*}"
  if ! printf '%s\n' "${OUT}" | grep -q -F "statement.${base}();"; then
    missing_patterns=$((missing_patterns + 1))
    bad "planted 'statement.${base}();' was NOT refused, yet the guard still lists '${pattern}' as forbidden"
  fi
done
if [ "${missing_patterns}" -eq 0 ]; then
  pass "all ${#DECLARED_CALL_PATTERNS[@]} planted patterns were individually refused and named"
fi

# ---------------------------------------------------------------------------
# Case 9: no side effects.
# ---------------------------------------------------------------------------
count="${MIRRORS}"
[ "${count}" -ge 12 ] || bad "expected the mutation cases to create several mirror trees, saw ${count}"
cleanup
leftover=0
for d in ${TEMPS}; do [ -d "${d}" ] && leftover=$((leftover + 1)); done
if [ "${leftover}" -eq 0 ]; then
  pass "all ${count} scratch mirror tree(s) were removed by the trap"
else
  bad "${leftover} of ${count} scratch mirror tree(s) survived cleanup"
fi
TEMPS=""

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

printf '\n=== analytics_source_guard_test summary ===\n'
printf 'checks: %s, failures: %s\n' "${checks}" "${failures}"
if [ "${failures}" -gt 0 ]; then
  printf 'FAIL: the analytics read-only guard test found %s failing assertion(s)\n' "${failures}" >&2
  exit 1
fi
printf 'ok: every analytics read-only guard assertion passed, including the mutation cases\n'
exit 0
