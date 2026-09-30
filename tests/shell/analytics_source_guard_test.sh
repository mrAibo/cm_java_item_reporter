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
#   6b. the SELECT-WRAPPED WRITE (Goal 03A section D). A literal whose write token is NOT
#                         the first word must be refused as well, because a prefix test
#                         on SELECT/WITH is not a structural read-only proof. Planted, one
#                         per fresh mirror: DB2's `SELECT * FROM FINAL TABLE (DELETE ...)`
#                         data-change table reference; a CTE carrying DELETE, UPDATE and
#                         INSERT tokens; a multi-statement `SELECT ...; DELETE ...`; a
#                         line comment and a block comment hiding a token; and the
#                         generic write-capable JDBC entry points `statement.execute(...)`,
#                         `ps.execute()`, `createStatement()`, which production analytics
#                         never needs and which the guard therefore forbids explicitly
#   7. no false positives O ordinary java.util / wrapper operations, a getter whose
#                         name merely RESEMBLES a forbidden call (updateCount,
#                         commitCount, rollbackCount), an English sentence that
#                         mentions commit, ordinary SELECT literals, and - the
#                         assertion that proves the rule is TOKEN-aware rather than a
#                         substring scan - UPDATED_AT / DELETED_FLAG / CREATE_TS, a
#                         two-literal concatenation, and a `"a/b*c and 1--2"` string
#                         that is not a SQL comment. This is the assertion that stops
#                         the next person from "fixing" a false positive by deleting the
#                         guard
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
  # Every mirror carries the ONE canonical literal-exempt path. The guard now treats a missing target as
  # a stale exemption and fails closed, so mutation cases that are about another rule must not accidentally
  # fail merely because their scratch repository omitted this contract file.
  printf '%s\n' \
    'package com.mraibo.cminsight.db;' \
    '' \
    '/** Scratch fixture: canonical literal-vocabulary exemption; call rules still scan this file. */' \
    'final class SqlAdmission {' \
    '  private SqlAdmission() { }' \
    '}' > "${dir}/${PRIMARY_REL}/SqlAdmission.java"

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
         | grep -E '^[^[:space:]#]' | LC_ALL=C sort -u)
printf 'declared call patterns: %s\n' "${DECLARED_CALL_PATTERNS[*]}"

# The declared STATEMENT-LITERAL patterns, read the same way. Case 1b below gives each of the
# seven literal rules a known-bad and a known-good sample, and this count is the tripwire for
# a NEW literal rule: adding one to the guard changes this number, so the control cannot stay
# silent about a rule it does not cover.
DECLARED_LITERAL_PATTERNS=()
while IFS= read -r pattern; do
  [ -n "${pattern}" ] || continue
  DECLARED_LITERAL_PATTERNS+=("${pattern}")
done < <(printf '%s\n' "${LIST_OUT}" \
         | awk '/^forbidden statement literals/ { inlist=1; next } /^exempt files/ { inlist=0 } inlist' \
         | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' \
         | grep -E '^[^[:space:]#]' | LC_ALL=C sort -u)
printf 'declared literal patterns: %s\n' "${DECLARED_LITERAL_PATTERNS[*]}"
if [ "${#DECLARED_LITERAL_PATTERNS[@]}" -eq 7 ]; then
  pass "the guard declares ${#DECLARED_LITERAL_PATTERNS[@]} statement-literal patterns, each of which case 1b controls"
else
  bad "the guard declares ${#DECLARED_LITERAL_PATTERNS[@]} statement-literal pattern(s); case 1b controls exactly 7 (after a rule was added or removed, give the new rule a bad/good sample pair and update this count)"
fi

# The realistic call for each declared rule, keyed by the rule's own text so the pairing is
# independent of the declaration ORDER (the guard's table is emitted sorted). Each entry is
# `pattern@@realistic call line`, and the assertions in case 8 are made in both directions:
# the pattern must match its line, and the guard must refuse and name that line.
DECLARED_CALL_SAMPLES=(
  'executeUpdate[[:space:]]*\(@@statement.executeUpdate("DELETE FROM ICMUT00001001");'
  'executeLargeUpdate[[:space:]]*\(@@statement.executeLargeUpdate("DELETE FROM ICMUT00001001");'
  'addBatch[[:space:]]*\(@@statement.addBatch("DELETE FROM ICMUT00001001");'
  'executeBatch[[:space:]]*\(@@statement.executeBatch();'
  'executeLargeBatch[[:space:]]*\(@@statement.executeLargeBatch();'
  'commit[[:space:]]*\(@@connection.commit();'
  'rollback[[:space:]]*\(@@connection.rollback();'
  'setSavepoint[[:space:]]*\(@@connection.setSavepoint();'
  'releaseSavepoint[[:space:]]*\(@@connection.releaseSavepoint();'
  'prepareCall[[:space:]]*\(@@connection.prepareCall("{call DO_SOMETHING()}");'
  '[^[:alnum:]_]execute[[:space:]]*\(@@statement.execute("SELECT 1 FROM ICMUT00001001");'
  'createStatement[[:space:]]*\(@@connection.createStatement();'
)
# The write/control VOCABULARY the guard declares, read from the guard's own --list
# output. Goal 03A section D requires the committed text guard and the runtime admission
# rule to name the same vocabulary, so this test asserts the guard really declares the
# whole set rather than merely that the file mentions one keyword. A keyword that vanished
# from every pattern would vanish from this list and fail here.
DECLARED_KEYWORDS="$(
  printf '%s\n' "${LIST_OUT}" \
    | awk '/^forbidden statement literals/ { inlist=1; next } /^exempt files/ { inlist=0 } inlist' \
    | grep -oE '[A-Z]{3,}' | LC_ALL=C sort -u | tr '\n' ' '
)"
printf 'declared keyword vocabulary: %s\n' "${DECLARED_KEYWORDS}"
for keyword in INSERT UPDATE DELETE MERGE TRUNCATE CREATE ALTER DROP GRANT REVOKE \
               CALL BEGIN COMMIT ROLLBACK EXEC EXECUTE SAVEPOINT FINAL OLD NEW; do
  case " ${DECLARED_KEYWORDS} " in
    *" ${keyword} "*) : ;;
    *) bad "the guard no longer declares ${keyword} in its statement-literal rules; the runtime admission rule and this guard would then disagree" ;;
  esac
done
pass "the guard declares every write/control keyword the runtime admission rule names"

# The same vocabulary read out of the RUNTIME rule's own source. Only literals that are a
# bare upper-case word are taken, so prose and assertion text around the list cannot be
# mistaken for vocabulary, and this comparison is what stops the two rules drifting apart
# in the direction that matters: a runtime rule wider than the source guard.
ADMISSION_KEYWORDS="$(
  grep -oE '"[A-Z]{3,}"' "${ROOT}/src/main/java/com/mraibo/cminsight/db/SqlAdmission.java" \
    | tr -d '"' | LC_ALL=C sort -u | tr '\n' ' '
)"
printf 'runtime admission vocabulary: %s\n' "${ADMISSION_KEYWORDS}"
for keyword in ${ADMISSION_KEYWORDS}; do
  case " ${DECLARED_KEYWORDS} " in
    *" ${keyword} "*) : ;;
    *) bad "SqlAdmission refuses ${keyword} but the committed guard does not name it; the runtime admission rule and the source guard have drifted apart" ;;
  esac
done
pass "every keyword the runtime admission rule refuses is also a keyword this guard refuses"

# The named exemption: a path, and one that exists. An exemption for a file that is not
# there would be a hole a later rename could hide in.
if printf '%s\n' "${LIST_OUT}" | grep -q -F 'exempt files'; then
  pass "the guard declares its exempt files rather than leaving the exemption implicit"
else
  bad "the guard does not declare which file is exempt from its literal rules"
fi
if printf '%s\n' "${LIST_OUT}" | grep -q -F 'SqlAdmission.java'; then
  pass "the declared exemption is the admission vocabulary table itself"
else
  bad "the guard's exempt list does not name SqlAdmission.java, so its own vocabulary literals would be refused"
fi
if [ -f "${ROOT}/src/main/java/com/mraibo/cminsight/db/SqlAdmission.java" ]; then
  pass "the exempt file exists in the committed tree"
else
  bad "the exempt file src/main/java/com/mraibo/cminsight/db/SqlAdmission.java does not exist: the exemption is stale"
fi

# ---------------------------------------------------------------------------
# Case 1b: the STATEMENT-LITERAL patterns are SENSITIVE - each one matches a known-bad
# sample, and none of them matches a known-good one.
#
# Why this case exists at all: a pattern that silently matches nothing is indistinguishable
# from a clean tree, which is the one outcome a guard must never produce. That is not
# hypothetical here. During Goal 03A's SQL hardening the statement-literal plants in cases
# 6/6b failed six times in a row against a guard that still exited 0 on the committed tree,
# and the literal rules had to be re-derived before they bit again. One contributing defect
# was a negated class written with the explicit ASCII range: that class contains the
# NON-ASCENDING range `Z-a`, whose meaning POSIX leaves undefined, so whether it matches a
# separator character is a property of the grep build rather than of this repository. The
# whole file now writes that class in its POSIX form, `[^[:alnum:]_]`, which has no range to
# misread. Measured honestly: reverting only that class in a COPY of the guard did NOT make
# the six plants pass again, so the class is recorded as a robustness fix rather than claimed
# as the cure - a rule whose meaning depends on the host tool is still the class of defect this
# project has paid for several times, and the sensitivity control below is what makes the next
# one visible instead of silent.
#
# Each rule below is given BOTH a known-bad sample (the guard must refuse the file containing
# it) and a known-good sample (the guard must accept that file). The bad half catches a rule
# that matches nothing; the good half catches a rule loosened until it matches everything.
# Both halves run against the guard itself, not a copy of its regex, and the table is keyed by
# the rule's DECLARATION NAME so the control survives the pattern text being reworded. A rule
# that loses its bad/good pair is reported rather than silently dropped, and the
# declared-pattern count asserted above trips when a NEW literal rule is added without one.
#
# The sample table: one known-bad and one known-good sample per declaration NAME in the guard.
# Keying on the name and resolving the name to the pattern the guard itself declares is what
# keeps this control independent of the regex text: a pattern that is reworded keeps its
# samples, and a sample stops being paired with its rule only if the DECLARATION disappears,
# which is reported rather than ignored.
#
# Every declared literal pattern must appear here. A pattern added to the guard without a
# sample WRONG-GUARDS this control, which is the safe direction: it fails loudly instead of
# going unchecked.
STATEMENT_RULE_BAD_SAMPLES=(
  'SQL_VERB_AFTER_QUOTE@@    String sql() { return "DELETE FROM ICMUT00001001"; }'
  'SQL_VERB_AFTER_QUOTE@@    String sql() { return "SELECT * FROM FINAL TABLE (DELETE FROM ICMUT00001001)"; }'
  'SQL_VERB_IN_PARENS@@    String sql() { return "WITH D AS (DELETE FROM ICMUT00001001) SELECT COUNT(*) FROM D"; }'
  'SQL_VERB_AFTER_SEPARATOR@@    String sql() { return "SELECT 1 FROM ICMUT00001001; DELETE FROM ICMUT00001001"; }'
  'SEPARATOR@@    String sql() { return "SELECT 1 FROM X; DROP TABLE X"; }'
  'COMMENT@@    String sql() { return "SELECT 1 FROM X -- DELETE FROM X"; }'
  'COMMENT@@    String sql() { return "SELECT 1 FROM X /* DELETE */ WHERE 1 = 0"; }'
  'TEXT_BLOCK_KEYWORD@@  String sql() { return """DELETE FROM ICMUT00001001"""; }'
  'JDBC_CALL_ESCAPE@@void g() throws Exception { connection.prepareCall("{call P()}"); }'
)
STATEMENT_RULE_GOOD_SAMPLES=(
  'SQL_VERB_AFTER_QUOTE@@    String sql() { return "SELECT UPDATED_AT, DELETED_FLAG, CREATE_TS FROM ICMUT00001001"; }'
  'SQL_VERB_AFTER_QUOTE@@    String sql() { return "SELECT NEW_ITEM_ID, OLD_ITEM_ID, CREATED_TODAY FROM ICMUT00001001"; }'
  'SQL_VERB_AFTER_QUOTE@@    String sql() { return "DELETE"; }'
  'SQL_VERB_AFTER_QUOTE@@    String sql() { return "SELECT ItemID FROM ICMUT00001001 UNION SELECT ItemID FROM ICMUT00001002"; }'
  'SQL_VERB_IN_PARENS@@    String sql() { return "SELECT COUNT(DISTINCT ITEMID) FROM ICMUT00001001"; }'
  'SQL_VERB_AFTER_SEPARATOR@@    String sql() { return "ItemType 1 has no physical root table; every segment 1..N must be present"; }'
  'SEPARATOR@@    String sql() { return "WITH LOGICAL_ITEMS AS (SELECT DISTINCT ITEMID FROM ICMUT00001001) SELECT COUNT(DISTINCT ITEMID) AS TOTAL_ITEMS FROM LOGICAL_ITEMS"; }'
  'COMMENT@@    String sql() { return "a/b*c and 1--2 are not SQL comments"; }'
  'TEXT_BLOCK_KEYWORD@@  String sql() { return """SELECT ITEMID FROM ICMUT00001001"""; }'
  'JDBC_CALL_ESCAPE@@    String sql() { return "SELECT 1 FROM ICMUT00001001 WHERE 1 = 0"; }'
)
sensitive_rules=0
insensitive_rules=0
uncovered_rules=0
for rule in SQL_VERB_AFTER_QUOTE SQL_VERB_IN_PARENS SQL_VERB_AFTER_SEPARATOR SEPARATOR COMMENT TEXT_BLOCK_KEYWORD JDBC_CALL_ESCAPE; do
  # The guard must still DECLARE the rule, and declaring it must still produce a pattern the
  # guard prints in --list. Without this the control could keep passing against a rule that
  # was renamed away from under it.
  declared_name="${rule}_PATTERN"
  if ! grep -q -E "^${declared_name}=" "${GUARD}"; then
    bad "the guard no longer declares ${declared_name}, so this sensitivity control has lost its subject"
    continue
  fi

  bad_samples=()
  good_samples=()
  for entry in "${STATEMENT_RULE_BAD_SAMPLES[@]}"; do
    [ "${entry%%@@*}" = "${rule}" ] && bad_samples+=("${entry#*@@}")
  done
  for entry in "${STATEMENT_RULE_GOOD_SAMPLES[@]}"; do
    [ "${entry%%@@*}" = "${rule}" ] && good_samples+=("${entry#*@@}")
  done
  if [ "${#bad_samples[@]}" -eq 0 ] || [ "${#good_samples[@]}" -eq 0 ]; then
    uncovered_rules=$((uncovered_rules + 1))
    bad "the guard declares ${declared_name} but this test has no bad/good sample pair for it; add one, or the rule is declared without a control"
    continue
  fi

  # The BAD samples must be refused by the guard, and refused by naming the planted file:
  # that is the rule doing its job on a real input, run through the real scanner.
  for bad_sample in "${bad_samples[@]}"; do
    new_mirror
    TR="${MIRROR}"
    rel="${PRIMARY_REL}/SensitivityProbe.java"
    plant "${TR}" "${rel}" "${bad_sample}"
    TREE="${TR}"
    run_guard
    if [ "${RC}" -ne 0 ] && printf '%s\n' "${OUT}" | grep -q -F "${rel}"; then
      pass "${declared_name} refuses a known-bad sample"
      sensitive_rules=$((sensitive_rules + 1))
    else
      bad "${declared_name} does NOT refuse its known-bad sample, so the rule matches nothing and a clean tree is indistinguishable from a guarded one: ${bad_sample}"
      insensitive_rules=$((insensitive_rules + 1))
    fi
  done

  # The GOOD samples must be accepted. Without this half, a rule loosened until it matches
  # everything would pass the check above.
  for good_sample in "${good_samples[@]}"; do
    new_mirror
    TG="${MIRROR}"
    rel="${PRIMARY_REL}/SensitivityProbe.java"
    plant "${TG}" "${rel}" "${good_sample}"
    TREE="${TG}"
    run_guard
    if [ "${RC}" -eq 0 ]; then
      pass "${declared_name} admits a known-good sample"
    else
      bad "${declared_name} refuses a known-good sample, so the rule is too broad: ${good_sample}"
      show "$(printf '%s\n' "${OUT}" | grep -F 'ERROR:' | head -n 1)"
    fi
  done
done
if [ "${insensitive_rules}" -eq 0 ]; then
  pass "all ${sensitive_rules} statement-literal rule samples proved SENSITIVE (each refused a known-bad sample)"
else
  bad "${insensitive_rules} statement-literal rule sample(s) matched nothing; a pattern that matches nothing is a disabled guard"
fi
if [ "${uncovered_rules}" -eq 0 ]; then
  pass "every statement-literal rule this control names is declared by the guard and has a bad/good sample pair"
fi
TREE="${ROOT}"

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
# Case 2b: the literal exemption is EXACT-PATH and LITERAL-ONLY.
# ---------------------------------------------------------------------------
for sibling_rel in \
  "${SECONDARY_REL}/review/SqlAdmission.java" \
  "${PRIMARY_REL}/review/SqlAdmission.java"; do
  new_mirror
  T2B="${MIRROR}"
  mkdir -p "$(dirname -- "${T2B}/${sibling_rel}")"
  printf '%s\n' \
    'package review;' \
    'final class SqlAdmission {' \
    '  String sql() { return "DELETE FROM ICMUT00001001"; }' \
    '}' > "${T2B}/${sibling_rel}"
  TREE="${T2B}"
  run_guard
  assert_refused "same-basename sibling gets NO literal exemption (${sibling_rel})" nonzero \
    "${T2B}/${sibling_rel}" 'Goal 03 analytics read-only violation'
done

# The canonical file may skip its own vocabulary literals, but JDBC call rules still scan it.
new_mirror
T2C="${MIRROR}"
canonical_rel="${PRIMARY_REL}/SqlAdmission.java"
plant "${T2C}" "${canonical_rel}" \
  'void write(java.sql.Statement statement) throws Exception { statement.executeUpdate("DELETE FROM X"); }'
TREE="${T2C}"
run_guard
assert_refused "canonical exempt path still refuses a real JDBC write call" nonzero \
  "${T2C}/${canonical_rel}" 'Goal 03 analytics read-only violation'

# Conversely, the canonical path really is exempt from the literal-vocabulary sub-rule.
new_mirror
T2D="${MIRROR}"
plant "${T2D}" "${canonical_rel}" 'String vocabulary() { return "DELETE FROM X"; }'
TREE="${T2D}"
run_guard
assert_accepted "canonical exact path alone may carry the forbidden vocabulary it defines"

# A rename/removal makes the exemption stale and is a failure, never an implicit pass.
new_mirror
T2E="${MIRROR}"
rm -f "${T2E}/${canonical_rel}"
TREE="${T2E}"
run_guard
assert_refused "removing the canonical literal-exempt path fails closed" nonzero \
  "${canonical_rel}" 'stale analytics literal exemption'

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
find "${T4}/${PRIMARY_REL}" -type f -name '*.java' -delete
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

# The STATEMENT shape of a bare-leading-verb literal, which is what the rule actually bites
# on: a statement verb is followed by whitespace and then its statement. A literal whose
# whole content is a bare verb with nothing after it (`"DELETE"`) is NOT refused, because that
# is an indistinguishable ordinary Java string; case 7 asserts that shape is accepted.
new_mirror
T6F="${MIRROR}"
rel="${PRIMARY_REL}/StatementShape.java"
plant "${T6F}" "${rel}" 'String sql() { return "COMMIT WORK"; }'
TREE="${T6F}"
run_guard
assert_refused "planted statement-shaped 'COMMIT WORK'" nonzero "${T6F}/${rel}" \
  'Goal 03 analytics read-only violation'

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

# ---------------------------------------------------------------------------
# Case 6b: the SELECT-WRAPPED WRITE, which is the whole point of Goal 03A section D.
#
# Every plant below begins with SELECT or WITH or carries its write token somewhere other
# than the first word, so a prefix test on the leading keyword would call all of them
# clean. That is the defect: "starts as a read" is not "is a read". Each plant gets its
# own fresh mirror, and every one of them must be refused AND named.
#
# Every plant is written with DOUBLE-quoted literal content, deliberately. A plant carrying
# a single-quoted SQL region cannot be written here without nesting an apostrophe in a
# single-quoted bash array entry, and a \u0027 escape survives as literal text through
# printf - which would plant a string that does not contain the apostrophe the test means
# to plant. That case is covered where it belongs: SqlAdmission refuses any string literal
# outright, and the shell guard's quote-anchored rule refuses a verb that follows either
# quote character, both of which are asserted in the Java twin and in the guard's own
# fixture for that rule.
# ---------------------------------------------------------------------------
planted_wrapped=(
  'data-change table reference@@String sql() { return "SELECT * FROM FINAL TABLE (DELETE FROM ICMADMIN.ICMUT00001001)"; }'
  'CTE carrying a DELETE token@@String sql() { return "WITH D AS (DELETE FROM ICMADMIN.ICMUT00001001) SELECT COUNT(*) FROM D"; }'
  'CTE carrying an UPDATE token@@String sql() { return "WITH U AS (UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1) SELECT COUNT(*) FROM U"; }'
  'CTE carrying an INSERT token@@String sql() { return "WITH I AS (INSERT INTO ICMADMIN.ICMUT00001001 (ITEMID) VALUES (1)) SELECT COUNT(*) FROM I"; }'
  'multi-statement SELECT ...; DELETE@@String sql() { return "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001; DELETE FROM ICMADMIN.ICMUT00001001"; }'
  'multi-statement SELECT ...; DROP@@String sql() { return "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001; DROP TABLE ICMADMIN.ICMUT00001001"; }'
  'line comment hiding a DELETE@@String sql() { return "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 -- DELETE FROM ICMADMIN.ICMUT00001001"; }'
  'block comment hiding a DELETE@@String sql() { return "SELECT 1 AS PRESENT /* DELETE */ FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0"; }'
  'data-change table reference, UPDATE form@@String sql() { return "SELECT * FROM FINAL TABLE (UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1)"; }'
)
wrapped_index=0
for entry in "${planted_wrapped[@]}"; do
  wrapped_index=$((wrapped_index + 1))
  statement="${entry#*@@}"
  new_mirror
  T6D="${MIRROR}"
  rel="${PRIMARY_REL}/Wrapped${wrapped_index}.java"
  plant "${T6D}" "${rel}" "${statement}"
  TREE="${T6D}"
  run_guard
  assert_refused "planted SELECT-wrapped write (${entry%%@@*})" nonzero "${T6D}/${rel}" \
    'Goal 03 analytics read-only violation'
  # The positive control for the FIRST and mandatory plant - DB2's data-change table
  # reference - is made here, while this mirror still exists: the refusal above must have
  # come from a file the guard really scanned, and re-reading it after the trap swept the
  # mirrors would prove nothing.
  if [ "${wrapped_index}" -eq 1 ]; then
    if grep -q -F 'FINAL TABLE (DELETE' "${T6D}/${rel}"; then
      pass "positive control: the SELECT-wrapped DATA-CHANGE TABLE plant is present in the file the guard scanned"
    else
      bad "the SELECT-wrapped plant is missing from ${T6D}/${rel}; its refusal evidence would be meaningless"
    fi
  fi
done

# The generic, write-capable JDBC entry points. Production analytics reaches the driver
# only through the prepare+executeQuery path, so these are forbidden explicitly rather
# than left as an escape hatch nobody was supposed to use. `executeQuery(` must NOT be
# refused - it is the one path the layer does use - which case 7 asserts below.
planted_generic_calls=(
  'Statement.execute(String)@@void g() throws Exception { statement.execute("SELECT 1 FROM ICMUT00001001"); }'
  'PreparedStatement.execute()@@void g() throws Exception { ps.execute(); }'
  'createStatement()@@void g() throws Exception { connection.createStatement(); }'
  'executeLargeUpdate()@@void g() throws Exception { statement.executeLargeUpdate("DELETE FROM X"); }'
  'addBatch()@@void g() throws Exception { statement.addBatch("DELETE FROM X"); }'
  'executeBatch()@@void g() throws Exception { statement.executeBatch(); }'
)
generic_index=0
for entry in "${planted_generic_calls[@]}"; do
  generic_index=$((generic_index + 1))
  statement="${entry#*@@}"
  new_mirror
  T6E="${MIRROR}"
  rel="${PRIMARY_REL}/GenericCall${generic_index}.java"
  plant "${T6E}" "${rel}" "${statement}"
  TREE="${T6E}"
  run_guard
  assert_refused "planted generic JDBC call (${entry%%@@*})" nonzero "${T6E}/${rel}" \
    'Goal 03 analytics read-only violation'
done

# The plant is really in the file the guard scanned, so the refusals above cannot
# have come from an unscanned tree.
if grep -q -F 'DELETE FROM ICMUT00001001' "${T6C}/${PRIMARY_REL}/TextBlock.java"; then
  pass "positive control: the text-block plant is present in a file the guard scanned"
else
  bad "the text-block plant is missing; the refusal evidence would be meaningless"
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
  '  // The token-aware controls: these identifiers merely CONTAIN a keyword fragment, and' \
  '  // a rule that refused them would refuse real column names and then be weakened.' \
  '  String bareWordLiterals() {' \
  '    // One-word ordinary strings. "NEW" and "COMMIT" are a state label and a log key, not' \
  '    // statements, and a guard that refused every one-word literal would refuse this shape' \
  '    // everywhere in a Java code base and then be deleted by whoever hit it.' \
  '    return "NEW" + "COMMIT" + "reads";' \
  '  }' \
  '' \
  '  String tokenAware() {' \  '    return "SELECT UPDATED_AT FROM ICMUT00001001 WHERE UPDATED_AT > 1";' \
  '  }' \
  '' \
  '  String tokenAwareToo() {' \
  '    return "SELECT DELETED_FLAG, CREATE_TS FROM ICMUT00001001";' \
  '  }' \
  '' \
  '  // Concatenated literals: the write keyword is never a token of one statement.' \
  '  String concatenated() {' \
  '    return "SELECT " + "COUNT(DISTINCT ITEMID) FROM ICMUT00001001";' \
  '  }' \
  '' \
  '  // Not SQL comments: no quote, space or parenthesis introduces the marker, and the' \
  '  // text after it is punctuation rather than comment text.' \
  '  String notAComment() {' \
  '    return "a/b*c and 1--2 are not SQL comments";' \
  '  }' \
  '' \
  '  String diagnosticProse() {' \
  '    return "ItemType 1 has no physical root table; every segment 1..N must be present";' \
  '  }' \
  '' \
  '  java.sql.ResultSet theOneQueryPath(java.sql.PreparedStatement ps) throws Exception {' \
  '    return ps.executeQuery();' \
  '  }' \
  '' \
  '  java.sql.ResultSet theNamedQueryPath() throws Exception {' \
  '    return statement.executeQuery("SELECT 1 FROM ICMUT00001001 WHERE 1 = 0");' \
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
# ONE realistic call per declared rule, looked up by the rule's own text. The assertions
# below are made in BOTH directions:
#
#   * each declared pattern must MATCH its realistic call, so a pattern cannot be declared
#     in a form that matches nothing (for example a bare `execute` rule that would also fire
#     on every `executeQuery`);
#   * the guard must refuse and name that call, so a rule that silently stopped biting is
#     reported rather than assumed.
#
# Reading the method name back out of the pattern was tried and abandoned: a declared
# pattern is a character class plus an escaped parenthesis, and every attempt to strip the
# tail with a parameter expansion or a sed anchor either mangled it or matched nothing,
# which made a correct guard look like it had missed a plant.
planted_realistic_calls=()
for pattern in "${DECLARED_CALL_PATTERNS[@]}"; do
  sample=''
  for entry in "${DECLARED_CALL_SAMPLES[@]}"; do
    if [ "${entry%%@@*}" = "${pattern}" ]; then
      sample="${entry#*@@}"
      break
    fi
  done
  if [ -z "${sample}" ]; then
    bad "the guard declares '${pattern}' but this test has no realistic call for it; add one to DECLARED_CALL_SAMPLES"
    continue
  fi
  planted_realistic_calls+=("${sample}")
  printf '    %s\n' "${sample}" >> "${T8}/${rel}"
  if printf '%s\n' "${sample}" | grep -q -E "(${pattern})"; then
    pass "declared pattern '${pattern}' matches its realistic call"
  else
    bad "declared pattern '${pattern}' does NOT match '${sample}': it is declared in a form that matches no realistic call"
  fi
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
for call in "${planted_realistic_calls[@]}"; do
  if ! printf '%s\n' "${OUT}" | grep -q -F "${call}"; then
    missing_patterns=$((missing_patterns + 1))
    bad "planted '${call}' was NOT refused by the guard, yet it matches a declared pattern"
  fi
done
if [ "${missing_patterns}" -eq 0 ]; then
  pass "all ${#planted_realistic_calls[@]} planted calls were individually refused and named"
fi

# ---------------------------------------------------------------------------
# Case 9: no side effects.
# ---------------------------------------------------------------------------
count="${MIRRORS}"
[ "${count}" -ge 25 ] || bad "expected the mutation cases to create several mirror trees, saw ${count}"
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
