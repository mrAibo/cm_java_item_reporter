#!/usr/bin/env bash
#
# CM Insight - Goal 03 section 7 structural JDBC read-only guard.
#
#   ./tests/shell/analytics_guard.sh [--list] [--help] [--root DIR]
#
# Goal 03 section 7 requires direct SQL in V1/V2 to be READ-ONLY and explicitly
# says not to rely on Connection.setReadOnly(true) alone: the JDBC contract
# defines read-only as a HINT, a driver may ignore it, and a database account with
# write privileges would happily accept a DELETE. The structural guarantee is
# therefore "the analytics source cannot express a write", and this script is that
# guarantee's single implementation.
#
# It is deliberately NOT named *_test.sh: tests/shell/run.sh discovers *_test.sh
# and *.test.sh, so a helper named this way is never run directly by the suite.
# The committed test tests/shell/analytics_source_guard_test.sh owns the
# assertions - both the acceptance case AND the mutation cases that prove the
# guard still bites - and build.sh can call this script the same way it calls
# tests/shell/ibm_guard.sh, so the rule is enforced even by somebody who never
# runs the suite.
#
# SUBJECT. The guard's subject is the analytics JDBC source tree:
#
#   src/main/java/com/mraibo/cminsight/db
#
# That tree must exist and must hold at least one .java file. A missing or empty
# tree is a LAYOUT failure (exit 2), never a clean result: a rename would
# otherwise disable the whole rule silently and forever. The statistics package is
# scanned as well when it exists, because the scan coordinator is where a query is
# issued from; a tree that exists but holds no file is refused there too.
#
# WHAT IS REFUSED
#
#   1. the JDBC write/control CALLS - executeUpdate, executeLargeUpdate,
#      addBatch/executeBatch/executeLargeBatch, commit, rollback, setSavepoint,
#      prepareCall - each anchored on the call parenthesis so a getter that merely
#      resembles one (updateCount(), commitCount()) is not a false positive;
#   2. the generic, write-capable JDBC CALLS that this layer never needs - a
#      statement-shaped `.execute(` (the PreparedStatement entry point that runs an
#      arbitrary statement text) and `.createStatement(`, both forbidden explicitly
#      rather than left as an escape hatch nobody is supposed to use. `executeQuery(`
#      and `executeQuery` are NOT matched: the pattern requires the method name to
#      end at the parenthesis, and it must follow a `statement`/`stmt`/`prepared`
#      receiver or be preceded by a non-identifier character;
#   3. a STRING LITERAL carrying a write/control SQL keyword (INSERT, UPDATE,
#      DELETE, MERGE, TRUNCATE, CREATE, ALTER, DROP, GRANT, REVOKE, CALL, BEGIN,
#      COMMIT, ROLLBACK, EXEC, EXECUTE, SAVEPOINT) or the data-change table
#      vocabulary (FINAL/OLD/NEW) as a WHOLE TOKEN - not only when the literal STARTS
#      with one. That is the strengthening Goal 03A section D requires:
#      `"SELECT * FROM FINAL TABLE (DELETE FROM X)"`, a CTE whose text carries a
#      DELETE/UPDATE/INSERT token, `"SELECT 1; DELETE FROM X"` and a token hidden
#      inside the literal's own quoted region are all statements whose write token is
#      not the first word, and a prefix test on the leading keyword calls every one of
#      them clean. Read the THREE literal patterns below for the exact boundaries; they
#      are separate because a token's boundary differs at each position, and a single
#      loose rule would refuse real column names. A literal whose WHOLE content is a bare
#      verb (`"NEW"`, `"COMMIT"`) is deliberately ADMITTED, because that shape is an
#      ordinary Java string, not a statement - the note above the patterns records why;
#   4. the JDBC CALL escape sequence `{call ...}`.
#
# The word "TOKEN" is load-bearing. A keyword only counts when it really is a token:
# `UPDATED_AT`, `DELETED_FLAG`, `CREATED_TODAY` and `INSERTED_BY` are ordinary identifiers
# and are NOT refused, even though each contains a write keyword as a substring. A guard
# that refuses real column names gets disabled by whoever hits it first; refusing whole
# tokens is what keeps this rule both strict and satisfiable.
#
# WHAT IS NOT REFUSED, ON PURPOSE
#
# Ordinary Java operations on the adapter's own locals - list.add, map.remove,
# atomic.set, a method named updateCount() - are never refused. A guard that
# cannot pass is a guard that gets disabled, and this project has already paid for
# that lesson once (see tests/shell/ibm_guard.sh). Every SQL-literal rule is anchored
# on an opening string quote, so only a statement-shaped literal is refused.
#
# THE ONE EXEMPTION, named rather than hidden
#
#   src/main/java/com/mraibo/cminsight/db/SqlAdmission.java
#
# That file IS the admission rule: its string literals are the forbidden vocabulary
# itself, so a rule that refuses a literal containing `DELETE` must obviously not fire
# on the list that defines `DELETE`. The exemption is a PATH, never a pattern, and the
# committed test asserts that the exempt file exists and that the same vocabulary can
# still be refused in every other file. This mirrors the single file-scoped exemption
# the IBM isolation guard already carries for the same reason: a named exception that
# a reviewer can see is safer than a pattern loosened until the rule stops biting.
#
# KNOWN LIMIT, stated rather than hidden: this script is line oriented (grep -E),
# so a multi-line Java text block whose OWN line does not begin the keyword is not
# caught here. The committed Java twin,
# AnalyticsSourceReadOnlyGuardTest, applies the same rule to the whole file text
# with a multi-line pattern and runs on every build, so the stronger check is the
# one that always runs.
#
# Message prefixes: "OK:" success, "ERROR:" fatal (exit != 0).
# Exit codes: 0 all rules hold, 1 a rule was violated, 2 usage or layout problem.

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./tests/shell/analytics_guard.sh [--list] [--help] [--root DIR]

Enforces the Goal 03 section 7 structural read-only rule for the analytics JDBC
source: no JDBC write/control call, no statement literal that begins with a
write/control SQL keyword, and no stored-procedure CALL execution.

Options:
  --list       print the scanned trees and the forbidden patterns
  --root DIR   scan the tree rooted at DIR instead of this script's repository.
               Used by the planted-control test, which mirrors the repository into
               a scratch directory and plants a violation there.
  --help       show this help

Exit codes: 0 all rules hold, 1 a rule was violated, 2 usage or layout.
USAGE
}

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
usage_fail() { printf 'ERROR: %s\n' "$*" >&2; usage >&2; exit 2; }

LIST_ONLY=false
ROOT_OVERRIDE=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --list) LIST_ONLY=true; shift ;;
    --root)
      [ "$#" -ge 2 ] || usage_fail "--root requires a directory"
      ROOT_OVERRIDE="$2"
      shift 2
      ;;
    *) usage_fail "unknown argument: $1" ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${SELF}")" && pwd)" || { printf 'ERROR: cannot locate %s\n' "${SELF}" >&2; exit 2; }
if [ -n "${ROOT_OVERRIDE}" ]; then
  ROOT="$(CDPATH= cd -- "${ROOT_OVERRIDE}" && pwd)" || { printf 'ERROR: --root %s is not a directory\n' "${ROOT_OVERRIDE}" >&2; exit 2; }
else
  ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)" || { printf 'ERROR: cannot resolve the repository root\n' >&2; exit 2; }
fi

# The analytics JDBC source tree: required, and required to be non-empty.
PRIMARY_REL='src/main/java/com/mraibo/cminsight/db'
# Scanned when present. The scan coordinator issues queries from here, so a write
# call added there is exactly as fatal as one added in db/.
SECONDARY_RELS=('src/main/java/com/mraibo/cminsight/statistics')

# The ONE exempt file, named by its exact repository-relative path rather than by
# basename or pattern. It is the admission rule itself: its string literals ARE the
# forbidden vocabulary, so only the statement-literal sub-rule may skip this exact file.
# JDBC call rules NEVER use this exemption.
EXEMPT_RELS=('src/main/java/com/mraibo/cminsight/db/SqlAdmission.java')

# ---------------------------------------------------------------------------
# The forbidden JDBC write/control calls.
#
# Every entry is anchored on the call parenthesis. That anchor is the whole
# reason this table is safe to keep broad: `updateCount()` does not match
# `executeUpdate[[:space:]]*\(`, and a javadoc sentence mentioning commit does not
# match `commit[[:space:]]*\(`.
# ---------------------------------------------------------------------------
FORBIDDEN_CALL_PATTERNS=(
  'executeUpdate[[:space:]]*\('
  'executeLargeUpdate[[:space:]]*\('
  'addBatch[[:space:]]*\('
  'executeBatch[[:space:]]*\('
  'executeLargeBatch[[:space:]]*\('
  'commit[[:space:]]*\('
  'rollback[[:space:]]*\('
  'setSavepoint[[:space:]]*\('
  'releaseSavepoint[[:space:]]*\('
  'prepareCall[[:space:]]*\('
  '[^[:alnum:]_]execute[[:space:]]*\('
  'createStatement[[:space:]]*\('
)

# The write/control VERB vocabulary, written once and reused by every literal rule below,
# so a keyword added to one rule cannot silently be missing from another.
SQL_VERBS='INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL|BEGIN|COMMIT|ROLLBACK|EXEC|EXECUTE|SAVEPOINT|FINAL|OLD|NEW'

# A STRING LITERAL carrying a write/control SQL keyword as a WHOLE TOKEN. What makes it
# token-aware is the character class on the RIGHT of the keyword: `[^[:alnum:]_]` requires
# the keyword to end where a token can end, so `UPDATED_AT`, `DELETED_FLAG`, `CREATE_TS`,
# `NEW_ITEM_ID` and `CREATED_TODAY` - real column names whose text merely CONTAINS one of
# these words - are admitted. On the left the rule is anchored, never floating: a keyword
# is only considered where a statement can actually start it.
#
# THREE anchored shapes, one per position a keyword token can occupy. They are deliberately
# not collapsed into one loose "the literal contains the word" rule: that version refuses
# real column names and gets deleted by whoever hits it first.
#
#   1. SQL_VERB_AFTER_QUOTE - the keyword follows a quote, which is the boundary of the
#      SELECT-wrapped case: `"SELECT * FROM FINAL TABLE (DELETE FROM X)"`,
#      `"WITH D AS (DELETE FROM X) SELECT ..."`, the concatenated form
#      `"SELECT " + "DELETE FROM X"`, a verb inside the literal's own single-quoted region
#      and a literal whose content is a statement (`"DELETE FROM X"`). The keyword must be
#      followed by whitespace and then by statement MATERIAL (`[A-Za-z0-9_(]`), which a
#      statement verb always is and an ordinary one-word string never is.
#   2. SQL_VERB_IN_PARENS - the keyword follows an opening parenthesis, which is the other
#      boundary of a common-table expression: `"(DELETE FROM X) ..."`, `"(UPDATE X SET ...)"`,
#      `"(INSERT INTO X ...)"`.
#   3. SQL_VERB_AFTER_SEPARATOR - a real statement separator inside the literal followed by
#      another statement: `"SELECT 1; DELETE FROM X"`. The `;[[:space:]]*` anchor is what
#      keeps ordinary Java text that merely contains a semicolon (a diagnostic listing
#      values) out of the result.
#
# WHAT THESE DELIBERATELY DO NOT REFUSE, and why that is not a hole. An earlier version also
# refused a literal whose ENTIRE content was a verb (`"NEW"`, `"COMMIT"`, `"DELETE"`). That
# rule could not tell a statement from an ordinary one-word Java string - `"NEW"` as a state
# label, `"COMMIT"` as a log key - so it was a false-positive machine for one of the most
# common literal shapes in a Java code base, and a guard that refuses those gets deleted. The
# `[[:space:]]+[A-Za-z0-9_(]` continuation in rule 1 replaces it and is strictly better: it
# catches every bare-verb STATEMENT (`"DELETE FROM X"`, `"COMMIT WORK"`, `"GRANT SELECT ..."`)
# while admitting a bare verb WORD (`"DELETE"`, `"NEW"`), because a statement verb is always
# followed by the whitespace and the token that a one-word string is not.
#
# A keyword inside a SINGLE-quoted region (`"... 'DELETE FROM Y' ..."`) is refused by rule 1
# as well (the quote is its own boundary) AND by the runtime rule, which refuses any quoted
# region outright: generated analytics SQL binds every value, so a literal cannot occur in an
# admitted statement at all.
SQL_VERB_AFTER_QUOTE_PATTERN="[\"'][[:space:]]*(${SQL_VERBS})[[:space:]]+[A-Za-z0-9_(]"
SQL_VERB_IN_PARENS_PATTERN="[(][[:space:]]*(${SQL_VERBS})[^[:alnum:]_]"
SQL_VERB_AFTER_SEPARATOR_PATTERN="[;][[:space:]]*(${SQL_VERBS})[^[:alnum:]_]"
# A statement separator inside a literal that is followed by another statement: the
# two-statement shape `SELECT ...; DELETE ...`. The trailing keyword keeps ordinary
# Java text that merely contains a semicolon (a diagnostic listing values) out of the
# result, so the rule stays satisfiable.
SEPARATOR_PATTERN=';[[:space:]]*(SELECT|WITH|INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL|VALUES)[^[:alnum:]_]'
# An SQL comment form inside a literal that actually carries comment text, which is how
# a token is hidden behind a comment: the marker is introduced by a quote, a space or a
# parenthesis (a real comment's position) and is followed by comment text. That
# separates `"SELECT 1 -- DELETE FROM X"` and `"/* DELETE */ SELECT 1"` (refused) from a
# harmless `"a/b*c"` or `"1--2"` (admitted).
COMMENT_PATTERN='(["]|[[:space:]]|[)])[[:space:]]*(--[[:space:]]*[A-Za-z]|/\*[[:space:]]*[A-Za-z])'
# A Java text-block opener immediately followed by the keyword on the same line.
TEXT_BLOCK_KEYWORD_PATTERN='"""[[:space:]]*(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL)'
# The JDBC stored-procedure escape, which only ever precedes a CALL. The trailing
# class keeps `{callee()` from being mistaken for a CALL escape.
JDBC_CALL_ESCAPE_PATTERN='[{][[:space:]]*[Cc][Aa][Ll][Ll][^[:alnum:]_]'

if [ "${LIST_ONLY}" = true ]; then
  printf 'scanned trees (root %s):\n' "${ROOT}"
  printf '  %s   (required, must be non-empty)\n' "${PRIMARY_REL}"
  for rel in "${SECONDARY_RELS[@]}"; do printf '  %s   (scanned when present)\n' "${rel}"; done
  printf 'forbidden JDBC write/control calls:\n'
  for pattern in "${FORBIDDEN_CALL_PATTERNS[@]}"; do printf '  %s\n' "${pattern}"; done
  printf 'forbidden statement literals:\n'
  for pattern in "${SQL_VERB_AFTER_QUOTE_PATTERN}" \
                 "${SQL_VERB_IN_PARENS_PATTERN}" "${SQL_VERB_AFTER_SEPARATOR_PATTERN}" \
                 "${SEPARATOR_PATTERN}" "${COMMENT_PATTERN}" \
                 "${TEXT_BLOCK_KEYWORD_PATTERN}" "${JDBC_CALL_ESCAPE_PATTERN}"; do
    printf '  %s\n' "${pattern}"
  done
  printf 'exempt files (the rule that names the vocabulary itself):\n'
  for rel in "${EXEMPT_RELS[@]}"; do printf '  %s\n' "${rel}"; done
  exit 0
fi

[ -d "${ROOT}/src" ] || { printf 'ERROR: missing %s/src; this does not look like a CM Insight working tree\n' "${ROOT}" >&2; exit 2; }

PRIMARY_DIR="${ROOT}/${PRIMARY_REL}"
[ -d "${PRIMARY_DIR}" ] || {
  printf 'ERROR: missing %s. The analytics read-only guard has nothing to scan, which is a LAYOUT failure and not a clean result: a rename or a moved source set must never disable this rule silently.\n' "${PRIMARY_DIR}" >&2
  exit 2
}

violations=0
scanned_files=0

# Validate the exemption contract globally, once. A missing canonical target is a stale
# exemption and therefore a guard failure; it must never degrade into "nothing matched".
for exempt_rel in "${EXEMPT_RELS[@]}"; do
  if [ ! -f "${ROOT}/${exempt_rel}" ]; then
    printf 'ERROR: stale analytics literal exemption: expected exact path %s\n' "${exempt_rel}" >&2
    violations=$((violations + 1))
  else
    printf 'OK: %s is the exact literal-vocabulary exemption; JDBC call rules still scan it\n' "${exempt_rel}"
  fi
done

report_violations() {
  # report_violations <label> <pattern> <directory>
  # Call/control rules scan EVERY file, including the canonical SqlAdmission.java.
  local label="$1" pattern="$2" directory="$3" line
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    printf 'ERROR: %s: %s\n' "${label}" "${line}" >&2
    violations=$((violations + 1))
  done < <(grep -REn --include='*.java' "(${pattern})" "${directory}" 2>/dev/null || true)
}

is_literal_exempt() {
  # is_literal_exempt <absolute-file>
  local file="$1" relative="${file#"${ROOT}/"}" exempt_rel
  for exempt_rel in "${EXEMPT_RELS[@]}"; do
    [ "${relative}" = "${exempt_rel}" ] && return 0
  done
  return 1
}

report_literal_violations() {
  # report_literal_violations <label> <pattern> <directory>
  # The literal-vocabulary exemption is exact-path-only; same-basename siblings are scanned.
  local label="$1" pattern="$2" directory="$3" file line
  while IFS= read -r -d '' file; do
    if is_literal_exempt "${file}"; then
      continue
    fi
    while IFS= read -r line; do
      [ -n "${line}" ] || continue
      printf 'ERROR: %s: %s:%s\n' "${label}" "${file}" "${line}" >&2
      violations=$((violations + 1))
    done < <(grep -En "(${pattern})" "${file}" 2>/dev/null || true)
  done < <(find "${directory}" -type f -name '*.java' -print0 2>/dev/null)
}

scan_tree() {
  # scan_tree <directory> <required|optional>
  local directory="$1" requirement="$2" count=0
  local rel="${directory#"${ROOT}/"}"
  if [ ! -d "${directory}" ]; then
    if [ "${requirement}" = required ]; then
      printf 'ERROR: %s is missing; the analytics read-only guard would scan nothing\n' "${rel}" >&2
      violations=$((violations + 1))
    else
      printf 'OK: %s is absent; nothing to scan there\n' "${rel}"
    fi
    return 0
  fi
  while IFS= read -r -d '' _file; do count=$((count + 1)); done \
    < <(find "${directory}" -type f -name '*.java' -print0 2>/dev/null)
  if [ "${count}" -eq 0 ]; then
    printf 'ERROR: %s exists but contains no .java file; a guard that scans nothing must not report success\n' "${directory}" >&2
    violations=$((violations + 1))
    return 0
  fi
  scanned_files=$((scanned_files + count))

  local pattern
  for pattern in "${FORBIDDEN_CALL_PATTERNS[@]}"; do
    report_violations "forbidden JDBC write/control call under ${rel}" "${pattern}" "${directory}"
  done
  report_literal_violations "statement literal carries a write/control keyword after a quote under ${rel}" \
    "${SQL_VERB_AFTER_QUOTE_PATTERN}" "${directory}"
  report_literal_violations "statement literal carries a write/control keyword inside parentheses under ${rel}" \
    "${SQL_VERB_IN_PARENS_PATTERN}" "${directory}"
  report_literal_violations "statement literal carries a write/control keyword after a separator under ${rel}" \
    "${SQL_VERB_AFTER_SEPARATOR_PATTERN}" "${directory}"
  report_literal_violations "statement literal carries a statement separator followed by another statement under ${rel}" \
    "${SEPARATOR_PATTERN}" "${directory}"
  report_literal_violations "statement literal carries an SQL comment form under ${rel}" \
    "${COMMENT_PATTERN}" "${directory}"
  report_literal_violations "text block begins with a write/control SQL keyword under ${rel}" \
    "${TEXT_BLOCK_KEYWORD_PATTERN}" "${directory}"
  report_literal_violations "stored-procedure CALL escape under ${rel}" \
    "${JDBC_CALL_ESCAPE_PATTERN}" "${directory}"
  return 0
}

scan_tree "${PRIMARY_DIR}" required
for rel in "${SECONDARY_RELS[@]}"; do
  scan_tree "${ROOT}/${rel}" optional
done

if [ "${violations}" -gt 0 ]; then
  fail "${violations} Goal 03 analytics read-only violation(s) above. Direct SQL in V1/V2 is SELECT-only: the database account may have SELECT-only privileges, but application safety must not depend on the operator having set that up."
fi

printf 'OK: the analytics source tree was scanned (%s .java file(s)) and holds no JDBC write/control call\n' "${scanned_files}"
printf 'OK: all Goal 03 analytics read-only guards hold\n'
