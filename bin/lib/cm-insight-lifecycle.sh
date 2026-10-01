#!/usr/bin/env bash
#
# CM Insight - shared lifecycle helpers for bin/start.sh, bin/status.sh and
# bin/stop.sh (SOURCED, never executed).
#
# The three scripts used to carry three copies of the same process/socket/health
# logic, which drifted. This module is the single implementation; the binding of a
# PID to "CM Insight" lives in exactly one place (ci_proc_identity), the socket
# lookup is exactly one port-scoped query (ci_listener_rows - there is deliberately
# NO process-name scan), and the health probe is bind-aware (ci_health_probe).
#
# Sourcing this file has NO side effects: no output, no exit, no directories. It
# sources bin/lib/cm-insight-addr.sh (pass CM_INSIGHT_LIB_DIR to override the
# directory, used by the tests under .tools/goal01a-scripts/).
#
# Interface:
#   ci_config_value <file> <key>        -> last value of the key, or nothing
#   ci_effective_bind <file>            -> web.bind, default 127.0.0.1
#   ci_effective_port <file>            -> web.port, default 8080 (0 stays 0)
#   ci_effective_home <repo root>       -> CM_INSIGHT_HOME or the repository root
#   ci_effective_config <home>          -> CM_INSIGHT_CONFIG or <home>/conf/application.properties
#   ci_is_alive <pid>                   -> 0 when the LOCAL pid exists and is not a zombie
#   ci_process_exists <pid>             -> 0 when the OS has the process in EITHER pid namespace
#   ci_proc_argv <pid>                  -> the real arguments, one per line
#   ci_argv_is_cm_insight               -> stdin = argv lines; 0 for a CM Insight token
#   ci_proc_identity <pid>              -> 0 CM Insight, 1 another process, 2 cannot tell
#   ci_proc_starttime <pid>             -> the process-INSTANCE start time, or nothing
#   ci_start_identity_next <state> <rc> <grace_elapsed> <instance>
#                                       -> the next startup identity state or a verdict:
#                                          positive | provisional | recycle (Goal 01B B)
#   ci_proc_cmdline <pid>               -> the command line as one line (display only)
#   ci_listener_rows <port>             -> "host|pid" per LISTENing TCP socket on port
#                                          (pid "-" when the platform hides the owner)
#   ci_bind_listener_rows <port> <bind> -> "host|pid|exact|covered|foreign" per row
#   ci_listener_pids <port> <bind>      -> pids of rows that serve the bind (exact first)
#   ci_pid_owns_serving_socket <pid> <port> <bind>
#                                       -> 0 when that pid owns a socket serving the bind
#   ci_signalable_pid <pid>             -> the pid `kill` accepts, or nothing (never guess)
#   ci_health_probe <port> <bind>       -> sets CI_HEALTH_KIND/CODE/URL/HOST/BIND
#
# Message prefixes: none (the module never prints status).

# ---------------------------------------------------------------------------
# module loading
# ---------------------------------------------------------------------------
CI_LIB_DIR="${CM_INSIGHT_LIB_DIR:-$(dirname -- "${BASH_SOURCE[0]:-$0}")}"
if ! command -v ci_addr_normalize >/dev/null 2>&1; then
  # shellcheck source=/dev/null
  . "${CI_LIB_DIR}/cm-insight-addr.sh"
fi

# ---------------------------------------------------------------------------
# configuration
# ---------------------------------------------------------------------------
ci_config_value() {
  # $1 = file, $2 = literal key; prints the effective value or nothing.
  # Like java.util.Properties, a duplicated key uses the LAST occurrence.
  _ci_file="$1"
  _ci_key="$2"
  [ -r "${_ci_file}" ] || return 0
  _ci_esc="$(printf '%s' "${_ci_key}" | sed 's/[.]/\\./g')"
  _ci_line="$(sed -n "s/^[[:space:]]*${_ci_esc}[[:space:]]*=[[:space:]]*//p" "${_ci_file}" 2>/dev/null)"
  _ci_line="${_ci_line##*$'\n'}"
  _ci_line="${_ci_line%$'\r'}"
  printf '%s' "${_ci_line}"
}

ci_effective_bind() {
  # $1 = config file; prints web.bind or the application default.
  _ci_eb="$(ci_config_value "${1:-}" web.bind)"
  _ci_eb="$(ci_addr_normalize "${_ci_eb}")"
  printf '%s' "${_ci_eb:-127.0.0.1}"
}

ci_effective_home() {
  # $1 = repository root; prints the application home by the ONE rule bin/cm-insight
  # implements (Goal 01A section D): CM_INSIGHT_HOME when set, else the repository
  # root. The launcher always passes that same value as -Dcminsight.home (after
  # CM_INSIGHT_JAVA_OPTS), so the JVM property, the launcher and these scripts cannot
  # disagree about where the application home is. A relative CM_INSIGHT_HOME stays
  # relative, exactly as in the launcher, and therefore resolves against the working
  # directory in both places.
  printf '%s' "${CM_INSIGHT_HOME:-${1:-}}"
}

ci_effective_config() {
  # $1 = application home; prints the configuration file path. CM_INSIGHT_CONFIG wins;
  # a RELATIVE value is resolved against the application home (not the caller's
  # working directory), which is what bin/cm-insight does for the same variable.
  _ci_ec="${CM_INSIGHT_CONFIG:-}"
  if [ -z "${_ci_ec}" ]; then
    printf '%s/conf/application.properties' "${1:-}"
    return 0
  fi
  case "${_ci_ec}" in
    /*|[A-Za-z]:[\\/]*) printf '%s' "${_ci_ec}" ;;
    *) printf '%s/%s' "${1:-}" "${_ci_ec}" ;;
  esac
}

ci_effective_port() {
  # $1 = config file; prints web.port or the application default (0 stays 0:
  # it means "bind a random free port", for which no health URL can be derived).
  _ci_ep="$(ci_config_value "${1:-}" web.port)"
  case "${_ci_ep}" in ''|*[!0-9]*) _ci_ep=8080 ;; esac
  printf '%s' "${_ci_ep}"
}

# ---------------------------------------------------------------------------
# process identity
# ---------------------------------------------------------------------------
ci_is_alive() {
  # $1 = pid; false for a process that has exited (including a zombie)
  _ci_pid="$1"
  kill -0 "${_ci_pid}" 2>/dev/null || return 1
  if [ -r "/proc/${_ci_pid}/stat" ]; then
    _ci_state="$(sed -n 's/^[^)]*) \([A-Za-z]\).*/\1/p' "/proc/${_ci_pid}/stat" 2>/dev/null)"
    [ "${_ci_state}" = "Z" ] && return 1
  fi
  return 0
}

ci_proc_argv() {
  # $1 = pid; prints the process arguments ONE PER LINE when the platform exposes
  # them, else nothing. /proc/<pid>/cmdline is NUL separated (verified on this host),
  # so the argument boundaries survive; that is what makes a structural identity check
  # possible at all. On platforms without /proc the `ps -p <pid> -o args=` fallback is
  # split on whitespace, which is a best effort and never a substring claim.
  _ci_ap="$1"
  if [ -r "/proc/${_ci_ap}/cmdline" ]; then
    tr '\0' '\n' < "/proc/${_ci_ap}/cmdline" 2>/dev/null | sed '/^$/d' || true
    return 0
  fi
  if command -v ps >/dev/null 2>&1; then
    _ci_ps="$(ps -p "${_ci_ap}" -o args= 2>/dev/null || true)"
    if [ -n "${_ci_ps}" ]; then
      printf '%s\n' "${_ci_ps}" | tr ' ' '\n' | sed '/^$/d'
    fi
  fi
}

ci_argv_is_cm_insight() {
  # stdin = a real argv, one argument per line (see ci_proc_argv).
  # 0 when a DOCUMENTED CM Insight identity token is present:
  #   * the exact main class argument          com.mraibo.cminsight.app.Main
  #   * a -jar TARGET whose basename is        cm-insight.jar
  #   * the launcher token                     <dir>/bin/cm-insight
  # SECURITY (Goal 01A section H, review finding H-2 including its residual): a
  # POSITIONAL argument that merely ends in cm-insight.jar is NOT proof. A class path
  # entry that happens to be named like our jar does not mean the process runs our jar:
  # a fixture started as `java ... FakeHttpService ... cm-insight.jar` must stay a
  # different process, and so must `-cp <dir>/cm-insight.jar` on its own. The jar token
  # therefore counts only as the target of a preceding -jar, and an unrelated option
  # value never counts at all. The exact main class or the launcher token remain
  # sufficient on their own, which is what every legitimate invocation of this
  # application produces (the launcher passes `-cp <dir>/cm-insight.jar <main class>`).
  _ci_prev=""
  _ci_main=false
  _ci_jar_target=false
  _ci_launcher=false
  while IFS= read -r _ci_tok; do
    [ -n "${_ci_tok}" ] || continue
    if [ "${_ci_prev}" = "-jar" ]; then
      _ci_jpath="$(printf '%s' "${_ci_tok}" | tr '\\' '/')"
      if [ "${_ci_jpath##*/}" = "cm-insight.jar" ]; then _ci_jar_target=true; fi
      _ci_prev="${_ci_tok}"
      continue
    fi
    case "${_ci_tok}" in
      -*) _ci_prev="${_ci_tok}"; continue ;;     # an option is never an identity token
    esac
    if [ "${_ci_tok}" = "com.mraibo.cminsight.app.Main" ]; then
      _ci_main=true
    else
      _ci_path="$(printf '%s' "${_ci_tok}" | tr '\\' '/')"
      _ci_base="${_ci_path##*/}"
      _ci_dir="${_ci_path%/*}"
      _ci_dirbase="${_ci_dir##*/}"
      if [ "${_ci_base}" = "cm-insight" ] && [ "${_ci_dirbase}" = "bin" ]; then
        _ci_launcher=true
      fi
    fi
    _ci_prev="${_ci_tok}"
  done
  [ "${_ci_main}" = true ] && return 0
  [ "${_ci_jar_target}" = true ] && return 0
  [ "${_ci_launcher}" = true ] && return 0
  return 1
}

ci_proc_identity() {
  # $1 = pid (an MSYS/shell pid or a platform pid; the platform mapping is tried when
  # /proc has no entry for the literal value); 0 = this application, 1 = a different
  # process, 2 = cannot tell.
  # The structural rules live in ci_argv_is_cm_insight: the exact main class argument
  # com.mraibo.cminsight.app.Main, the target of a -jar option whose basename is
  # cm-insight.jar, or the launcher token <dir>/bin/cm-insight. There is deliberately NO
  # "contains the string" fallback and no bare-positional-jar rule: a process is never
  # ours merely because some unrelated argument or option value mentions the name, and a
  # bare JVM (no such token) stays a different process.
  _ci_argv="$(ci_proc_argv "$1")"
  if [ -z "${_ci_argv}" ]; then
    _ci_map="$(ci_msyspid_of "$1")"
    if [ -n "${_ci_map}" ]; then
      _ci_argv="$(ci_proc_argv "${_ci_map}")"
    fi
  fi
  if [ -z "${_ci_argv}" ]; then return 2; fi
  printf '%s\n' "${_ci_argv}" | ci_argv_is_cm_insight
}

ci_proc_cmdline() {
  # $1 = pid; the arguments as ONE line. DISPLAY ONLY: never use this for an identity
  # decision (that is ci_proc_identity), because a one-line form has no argument
  # boundaries and invites the unanchored substring matching review finding H-2 banned.
  ci_proc_argv "$1" | tr '\n' ' '
}

ci_proc_starttime() {
  # $1 = pid; prints the process-INSTANCE start time (Linux /proc/<pid>/stat field 22,
  # clock ticks since boot) or nothing when the platform cannot report it.
  #
  # WHY (Goal 01B section B, independent review finding F1): the kernel sets this value
  # ONCE, when the process is created, and execve() does NOT change it. A launcher chain
  # therefore keeps the SAME start time across every re-exec (launcher -> wrapper -> java),
  # while a REUSED pid belongs to a different process instance and reports a different
  # value. That is what makes "is this still the process we launched?" decidable WITHOUT a
  # heuristic: it separates a legitimate re-exec through a non-identity image from real PID
  # reuse. It is corroborating evidence for a VERDICT only - it never replaces argv identity
  # and it never authorises signalling anything.
  #
  # /proc/<pid>/stat is `pid (comm) state ppid ...`: comm may itself contain spaces and
  # parentheses, so everything up to the LAST ')' is stripped first; starttime is then field
  # 20 of the remainder (state=1, ppid=2, pgrp=3, session=4, ..., starttime=20).
  _ci_st_pid="$1"
  case "${_ci_st_pid}" in ''|*[!0-9]*) return 1 ;; esac
  if [ ! -r "/proc/${_ci_st_pid}/stat" ]; then
    # MSYS/Cygwin: the pid may have to be mapped into the other namespace first.
    _ci_st_map="$(ci_msyspid_of "${_ci_st_pid}")"
    if [ -n "${_ci_st_map}" ] && [ -r "/proc/${_ci_st_map}/stat" ]; then
      _ci_st_pid="${_ci_st_map}"
    else
      return 1
    fi
  fi
  _ci_st_value="$(sed -e 's/^.*) //' "/proc/${_ci_st_pid}/stat" 2>/dev/null | awk '{print $20}')"
  case "${_ci_st_value}" in ''|*[!0-9]*) return 1 ;; esac
  printf '%s' "${_ci_st_value}"
}

# ---------------------------------------------------------------------------
# startup identity state (Goal 01B section B)
# ---------------------------------------------------------------------------
ci_start_identity_next() {
  # $1 = current state: provisional | positive
  # $2 = ci_proc_identity result for the tracked pid: 0 (ours) | 1 (different) | 2 (cannot tell)
  # $3 = "true" when the bounded launch grace has elapsed (the caller owns the clock)
  # $4 = process-INSTANCE evidence for a contradictory sample (i.e. $2 == 1):
  #        different  the pid denotes ANOTHER process instance (its ci_proc_starttime changed)
  #        same       the pid is still the SAME instance (start time unchanged across re-exec)
  #        unknown    the platform could not witness it (default)
  # prints one of:
  #   positive     an observation proved this pid is this application
  #   provisional  no verdict: the caller keeps its state and keeps waiting
  #   recycle      a contradictory reading is now proof of PID reuse: refuse and never signal
  #
  # WHY this state exists at all (evidence, Actions run 36443449216 on SHA 9c7168aa):
  # bin/start.sh backgrounds the launcher and only then samples the identity. Until that
  # child has exec'd its FIRST image it still carries the FORKED PARENT's command line
  # (measured: a child blocked before its first execve reports the parent's argv, and
  # ci_proc_identity correctly answers 1 for it). At that moment the sample says "a
  # different process" about a PID that becomes this application a moment later - in that
  # run the log was still 0 bytes when the verdict was reached, and the SAME PID 3451 was
  # our JVM a fraction of a second later. One early negative sample is therefore not proof
  # of PID reuse; the identity stays PROVISIONAL until either one POSITIVE observation
  # occurs or the caller's bounded grace elapses.
  #
  # WHY the instance argument exists (independent review finding F1): a launcher may
  # legitimately re-exec through an image whose command line does not name CM Insight
  # (operator wrapper, `nice`/`systemd-run`/`timeout`, a bin/cm-insight that shells out).
  # After a POSITIVE observation, ONE contradictory sample must therefore not be conclusive:
  # the reviewer executed exactly that shape and got a false "PID reused" verdict. A
  # contradiction is only latched as recycle when the pid is PROVEN to be another process
  # instance ("different"); "same" is a re-exec inside the process we already identified,
  # and "unknown" is unwitnessable, which must fail towards waiting, never towards a verdict.
  #
  # Transition table (all four inputs are caller-supplied; this function is pure):
  #   any         + 0 + any   + any        -> positive    our command line: settled
  #   any         + 2 + any   + any        -> unchanged   uninspectable proves nothing
  #   any         + 1 + any   + different  -> recycle     proven PID reuse (instance changed)
  #   provisional + 1 + false + same       -> provisional the same instance is still starting
  #   provisional + 1 + true  + same       -> provisional same instance: NOT a reuse verdict
  #   provisional + 1 + true  + unknown    -> recycle     fallback where the instance cannot be
  #                                                       witnessed: the WHOLE bounded grace
  #                                                       produced no positive identity
  #   provisional + 1 + false + unknown    -> provisional
  #   positive    + 1 + any   + same       -> provisional same process instance: NOT reuse
  #   positive    + 1 + any   + unknown    -> provisional cannot witness it: NOT a verdict
  # "unchanged" means the printed verdict is the input state (positive stays positive,
  # provisional stays provisional). The ONE decisive latch is a changed process instance;
  # everything else waits, and the caller's own bounded timeout decides, with nothing
  # published and nothing signalled either way. "Cannot tell" never moves the state in either
  # direction: it is neither evidence of identity nor evidence of reuse.
  _ci_si_state="${1:-provisional}"
  _ci_si_rc="${2:-2}"
  _ci_si_grace_elapsed="${3:-false}"
  _ci_si_instance="${4:-unknown}"
  case "${_ci_si_rc}" in
    0) printf 'positive' ;;
    1)
      if [ "${_ci_si_instance}" = "different" ]; then
        # Decisive in EITHER state: the pid now denotes another process instance.
        printf 'recycle'
      elif [ "${_ci_si_state}" = "positive" ] || [ "${_ci_si_instance}" = "same" ]; then
        # A positive identity was already established, or the pid is provably still the
        # process instance that was launched: a contradictory image is not a reuse verdict.
        printf 'provisional'
      else
        # Never positively identified AND the platform cannot witness the instance: the
        # bounded grace decides, exactly as shipped and reviewed.
        case "${_ci_si_grace_elapsed}" in
          true) printf 'recycle' ;;
          *) printf 'provisional' ;;
        esac
      fi
      ;;
    *)
      case "${_ci_si_state}" in
        positive) printf 'positive' ;;
        *) printf 'provisional' ;;
      esac
      ;;
  esac
}

ci_process_exists() {
  # $1 = pid; 0 when the OS still has that process, in EITHER pid namespace.
  # SECURITY/RELIABILITY (review findings H-3/H-4): `kill -0` plus /proc alone is not
  # enough on MSYS/Cygwin. A process that exec'd a native binary (java.exe) can vanish
  # from the MSYS pid space while the Windows process keeps running and serving, which
  # made bin/start.sh report "exited during startup ... nothing is running" for a
  # healthy instance and made bin/stop.sh call a serving instance "not running". The
  # platform process table is therefore consulted before anything is declared gone.
  _ci_px="$1"
  case "${_ci_px}" in ''|*[!0-9]*) return 1 ;; esac
  if ci_is_alive "${_ci_px}"; then return 0; fi
  if [ -n "$(ci_winpid_of "${_ci_px}")" ]; then return 0; fi
  if [ -n "$(ci_msyspid_of "${_ci_px}")" ]; then return 0; fi
  return 1
}

ci_is_msys_platform() {
  # 0 on Git Bash / MSYS / Cygwin, where the shell pid, the /proc pid and the
  # Windows pid reported by the socket table live in different namespaces.
  case "$(uname -s 2>/dev/null || printf 'unknown')" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}

ci_winpid_of() {
  # $1 = shell/MSYS pid; prints the Windows pid that `ps -W` reports for it, else
  # nothing. On non-MSYS platforms it prints nothing (the pid is already the process pid).
  if ci_is_msys_platform; then
    command -v ps >/dev/null 2>&1 || return 0
    ps -W 2>/dev/null | tr -d '\r' | awk -v p="${1:-}" '$1==p {print $4; exit}'
  fi
}

ci_msyspid_of() {
  # $1 = Windows pid; prints the MSYS pid that tracks it, else nothing.
  if ci_is_msys_platform; then
    command -v ps >/dev/null 2>&1 || return 0
    ps -W 2>/dev/null | tr -d '\r' | awk -v w="${1:-}" '$4==w {print $1; exit}'
  fi
}

ci_same_process() {
  # $1, $2 = pids, possibly from different namespaces (an MSYS pid from a PID file
  # vs a Windows pid from the socket table). 0 when both denote the same process.
  [ -n "${1:-}" ] && [ -n "${2:-}" ] || return 1
  [ "$1" = "$2" ] && return 0
  _ci_sp_a="$(ci_winpid_of "$1")"
  [ -n "${_ci_sp_a}" ] && [ "${_ci_sp_a}" = "$2" ] && return 0
  _ci_sp_b="$(ci_winpid_of "$2")"
  [ -n "${_ci_sp_b}" ] && [ "${_ci_sp_b}" = "$1" ] && return 0
  _ci_sp_c="$(ci_msyspid_of "$1")"
  [ -n "${_ci_sp_c}" ] && [ "${_ci_sp_c}" = "$2" ] && return 0
  _ci_sp_d="$(ci_msyspid_of "$2")"
  [ -n "${_ci_sp_d}" ] && [ "${_ci_sp_d}" = "$1" ] && return 0
  return 1
}

# ---------------------------------------------------------------------------
# listening sockets (port-scoped only)
# ---------------------------------------------------------------------------
ci_listener_rows() {
  # $1 = port; prints "host|pid" for every LISTENing TCP socket on that port, or
  # nothing when the port is not held or the platform cannot be queried. Only
  # genuinely PORT-SCOPED sources are used: ss -ltnp (iproute2) or netstat -ano
  # (Windows/MSYS and net-tools). There is deliberately NO process-name scan: an
  # unrelated CM Insight JVM on another port must never be reported as this port's
  # owner, and an unknown owner stays "-", never a guess.
  _ci_lport="${1:-}"
  case "${_ci_lport}" in ''|*[!0-9]*) return 0 ;; esac
  _ci_rows=""
  if command -v ss >/dev/null 2>&1; then
    # ss local address column is field 4 of: State Recv-Q Send-Q Local Peer Process
    # A header row is skipped by name, not by line number (ss -H is not universal).
    _ci_rows="$(ss -ltnp 2>/dev/null | tr -d '\r' | awk -v port="${_ci_lport}" '
      NR == 1 && $1 == "State" { next }
      {
        a = $4;
        if (a !~ /:[0-9]+$/) next;
        prt = a; sub(/^.*:/, "", prt);
        if (prt + 0 != port + 0) next;
        h = a; sub(/:[0-9]+$/, "", h);
        gsub(/^\[/, "", h); gsub(/\]$/, "", h);
        if (h == "") h = "*";
        p = "-";
        if (match($0, /pid=[0-9]+/)) p = substr($0, RSTART + 4, RLENGTH - 4);
        print h "|" p;
      }' || true)"
  fi
  if [ -z "${_ci_rows}" ] && command -v netstat >/dev/null 2>&1; then
    # Field layout (Windows netstat -ano and net-tools): Proto Local Foreign State PID
    # The state text is LOCALIZED ("ABHOEREN"), so listening rows are recognized by
    # their wildcard peer address instead of by the state word.
    _ci_rows="$(netstat -ano 2>/dev/null | tr -d '\r' | awk -v port="${_ci_lport}" '
      {
        if (tolower($1) !~ /^tcp/) next;
        a = $2; f = $3; p = $5;
        if (a !~ /:[0-9]+$/) next;
        prt = a; sub(/^.*:/, "", prt);
        if (prt + 0 != port + 0) next;
        if (f !~ /^(0\.0\.0\.0|\*|::|\[::\]):(0|\*)$/) next;
        h = a; sub(/:[0-9]+$/, "", h);
        gsub(/^\[/, "", h); gsub(/\]$/, "", h);
        if (h == "") h = "*";
        if (p !~ /^[0-9]+$/) p = "-";
        print h "|" p;
      }' || true)"
  fi
  [ -z "${_ci_rows}" ] || printf '%s\n' "${_ci_rows}" | sort -u
}

ci_bind_listener_rows() {
  # $1 = port, $2 = configured bind; prints "host|pid|exact|covered|foreign" for every
  # listening socket on the port, with the socket classified against the bind:
  #   exact   bound to the configured address itself
  #   covered bound to a wildcard that also serves the configured address
  #   foreign bound to an address this configuration cannot be listening on
  # Callers act only on exact/covered rows and report foreign rows as somebody else's.
  _ci_bl_port="${1:-}"
  _ci_bl_bind="${2:-}"
  ci_listener_rows "${_ci_bl_port}" | while IFS='|' read -r _ci_bl_host _ci_bl_pid; do
    [ -n "${_ci_bl_host}" ] || continue
    printf '%s|%s|%s\n' "${_ci_bl_host}" "${_ci_bl_pid}" "$(ci_addr_socket_class "${_ci_bl_host}" "${_ci_bl_bind}")"
  done
}

ci_listener_pids() {
  # $1 = port, $2 = bind; prints the distinct PIDs of the sockets that serve the
  # bind, exact matches first, so "who owns this port" is never a process-name guess.
  # Rows with an unknown owner ("-") are skipped: an unknown owner is not a target.
  _ci_lp_port="${1:-}"
  _ci_lp_bind="${2:-}"
  for _ci_lp_cls in exact covered; do
    ci_bind_listener_rows "${_ci_lp_port}" "${_ci_lp_bind}" | while IFS='|' read -r _ci_lp_host _ci_lp_pid _ci_lp_class; do
      [ "${_ci_lp_class}" = "${_ci_lp_cls}" ] || continue
      case "${_ci_lp_pid}" in ''|-|*[!0-9]*) continue ;; esac
      printf '%s\n' "${_ci_lp_pid}"
    done
  done | awk '!seen[$0]++'
}

ci_pid_owns_serving_socket() {
  # $1 = pid (ANY namespace), $2 = port, $3 = bind. 0 when a listening socket that
  # serves the configured bind is owned by the process $1 denotes. This is what keeps a
  # PID file written in the other pid namespace from being declared "not running" while
  # the instance still serves (review finding H-4).
  _ci_ps_port="${2:-}"
  _ci_ps_bind="${3:-}"
  case "${_ci_ps_port}" in ''|0|*[!0-9]*) return 1 ;; esac
  while IFS='|' read -r _ci_ps_host _ci_ps_owner _ci_ps_class; do
    [ -n "${_ci_ps_host}" ] || continue
    case "${_ci_ps_class}" in foreign|sibling) continue ;; esac
    case "${_ci_ps_owner}" in ''|-|*[!0-9]*) continue ;; esac
    if ci_same_process "${_ci_ps_owner}" "$1"; then return 0; fi
  done < <(ci_bind_listener_rows "${_ci_ps_port}" "${_ci_ps_bind}")
  return 1
}

ci_socket_host_matches() {
  # $1, $2 = socket hosts (a socket-table host, a configured bind, or a probe host).
  # 0 when both denote the SAME socket address, compared in ci_addr_socket_normalize()
  # form so the spellings of one address are equal (Goal 01B sections C and D):
  #   127.0.0.1 and ::ffff:127.0.0.1      (IPv4-mapped IPv6 representation)
  #   [::ffff:127.0.0.1] and ::ffff:127.0.0.1  (bracketed tool output)
  #   127.0.0.1 and 127.0.0.01/127.000.000.001 (the same address, differently padded)
  # This is a SOCKET-EQUIVALENCE helper for ownership/attribution ONLY: it decides
  # whether the row a health answer arrived on is the row that is about to be tied to a
  # PID. It never grants loopback privilege - ci_addr_is_loopback stays the one exposure
  # rule, and a mapped spelling of a non-loopback address still normalises to that
  # non-loopback address (::ffff:192.0.2.10 is 192.0.2.10, never 127.0.0.1).
  [ -n "${1:-}" ] && [ -n "${2:-}" ] || return 1
  [ "$(ci_addr_socket_normalize "$1")" = "$(ci_addr_socket_normalize "$2")" ]
}

ci_signalable_pid() {
  # $1 = pid; prints the pid that `kill` accepts for it on this platform, or nothing
  # when the value cannot be resolved into the local process namespace. NOTHING is
  # printed when the resolution is uncertain: passing an unresolved platform pid to
  # `kill` could signal whatever local process happens to carry that number.
  _ci_sg_pid="$1"
  case "${_ci_sg_pid}" in ''|*[!0-9]*) return 1 ;; esac
  if ci_is_alive "${_ci_sg_pid}"; then printf '%s' "${_ci_sg_pid}"; return 0; fi
  _ci_sg_map="$(ci_msyspid_of "${_ci_sg_pid}")"
  if [ -n "${_ci_sg_map}" ] && ci_is_alive "${_ci_sg_map}"; then printf '%s' "${_ci_sg_map}"; return 0; fi
  return 1
}

ci_pid_is_running() {
  # $1 = pid, $2 = port, $3 = bind; 0 when that pid is behind a live process in either
  # pid namespace OR owns a listening socket that serves the configured bind. Both nets
  # are needed (review findings H-3/H-4): the process table alone misses a native process
  # that left the shell pid space, and the socket table alone misses a bound-but-hung
  # instance whose socket the platform does not attribute.
  ci_process_exists "$1" && return 0
  ci_pid_owns_serving_socket "$1" "${2:-}" "${3:-}" && return 0
  return 1
}

# ---------------------------------------------------------------------------
# bind-aware health probe
# ---------------------------------------------------------------------------
ci_health_probe() {
  # $1 = port, $2 = bind; probes every candidate address for the configured bind
  # until one answers with the exact CM Insight marker. Sets:
  #   CI_HEALTH_KIND  our | foreign | none | unknown
  #   CI_HEALTH_CODE  HTTP status of the decisive answer (000 when none/unknown)
  #   CI_HEALTH_URL   the URL of the decisive answer ("" when none/unknown)
  #   CI_HEALTH_HOST  the host of the decisive answer
  #   CI_HEALTH_BIND  the probe host the configuration prefers
  # "our" means the health body carries the cm-insight marker, so a different HTTP
  # service on the same port is reported as foreign, never as ours.
  CI_HEALTH_KIND="unknown"
  CI_HEALTH_CODE="000"
  CI_HEALTH_URL=""
  CI_HEALTH_HOST=""
  CI_HEALTH_BIND="$(ci_addr_probe_host "${2:-}")"
  _ci_hp_port="${1:-}"
  case "${_ci_hp_port}" in ''|0|*[!0-9]*) return 0 ;; esac
  command -v curl >/dev/null 2>&1 || return 0
  _ci_hp_foreign_url=""
  _ci_hp_foreign_code=""
  for _ci_hp_host in $(ci_addr_probe_hosts "${2:-}"); do
    _ci_hp_url="$(ci_health_url "${_ci_hp_host}" "${_ci_hp_port}")"
    _ci_hp_tmp="${TMPDIR:-/tmp}/cm-insight-probe.$$.${RANDOM:-0}"
    _ci_hp_code="$(curl -sS -o "${_ci_hp_tmp}" -w '%{http_code}' --max-time 2 "${_ci_hp_url}" 2>/dev/null || true)"
    _ci_hp_body="$(cat "${_ci_hp_tmp}" 2>/dev/null || true)"
    rm -f "${_ci_hp_tmp}" 2>/dev/null || true
    case "${_ci_hp_code}" in
      ''|000) continue ;;
    esac
    if [ "${_ci_hp_code}" = "200" ] && ci_health_body_is_marker "${_ci_hp_body}"; then
      CI_HEALTH_KIND="our"
      CI_HEALTH_CODE="${_ci_hp_code}"
      CI_HEALTH_URL="${_ci_hp_url}"
      CI_HEALTH_HOST="${_ci_hp_host}"
      return 0
    fi
    if [ -z "${_ci_hp_foreign_url}" ]; then
      _ci_hp_foreign_url="${_ci_hp_url}"
      _ci_hp_foreign_code="${_ci_hp_code}"
    fi
  done
  if [ -n "${_ci_hp_foreign_url}" ]; then
    CI_HEALTH_KIND="foreign"
    CI_HEALTH_CODE="${_ci_hp_foreign_code}"
    CI_HEALTH_URL="${_ci_hp_foreign_url}"
  else
    CI_HEALTH_KIND="none"
  fi
}

ci_bind_display() {
  # $1 = bind, $2 = port; prints "<bind>:<port>" with an IPv6 bind bracketed.
  _ci_bd_bind="$(ci_addr_normalize "${1:-}")"
  case "${_ci_bd_bind}" in
    \[*\]) : ;;
    *:*) _ci_bd_bind="[${_ci_bd_bind}]" ;;
  esac
  printf '%s:%s' "${_ci_bd_bind}" "${2:-}"
}

ci_describe_socket() {
  # $1 = socket host, $2 = exact|covered|foreign, $3 = bind, $4 = port
  # One honest sentence about a listening socket and its relation to the configured
  # bind, shared by start.sh/status.sh/stop.sh so the three never drift. A socket host
  # that differs from the configured bind is never described as "the configured
  # address": a wildcard bind is reached through several socket forms (0.0.0.0, ::,
  # the loopback literals), and each is reported as what it is.
  _ci_ds_host="$(ci_addr_normalize "${1:-}")"
  _ci_ds_cls="${2:-}"
  _ci_ds_bind="$(ci_addr_normalize "${3:-}")"
  _ci_ds_display="$(ci_bind_display "${_ci_ds_host}" "${4:-}")"
  _ci_ds_wild=false
  case "${_ci_ds_host}" in ''|0.0.0.0|\*|::|\[::\]) _ci_ds_wild=true ;; esac
  case "${_ci_ds_cls}" in
    exact)
      if [ "${_ci_ds_host}" = "${_ci_ds_bind}" ]; then
        if [ "${_ci_ds_wild}" = true ]; then
          printf 'socket %s (the configured wildcard address: every interface this platform offers is served)' "${_ci_ds_display}"
        else
          printf 'socket %s (bound to the configured address)' "${_ci_ds_display}"
        fi
      elif [ "${_ci_ds_wild}" = true ]; then
        printf 'socket %s (a wildcard form that serves the configured bind %s)' "${_ci_ds_display}" "${_ci_ds_bind}"
      elif [ "$(ci_addr_socket_normalize "${_ci_ds_host}")" = "$(ci_addr_socket_normalize "${_ci_ds_bind}")" ]; then
        # Two spellings of one socket address: a Linux socket table may report a 127.0.0.1
        # listener as ::ffff:127.0.0.1. Saying "one of the addresses the bind resolves to"
        # would read as if these were two different sockets (Goal 01B section C/D). The
        # wording stays direction-neutral: either spelling may be the mapped one.
        printf 'socket %s (the SAME socket address as the configured bind %s, only spelled differently: %s and %s denote one socket - an IPv4-mapped IPv6 spelling and its IPv4 form are the same listener)' "${_ci_ds_display}" "${_ci_ds_bind}" "${_ci_ds_host}" "${_ci_ds_bind}"
      else
        printf 'socket %s (one of the addresses the configured bind %s resolves to)' "${_ci_ds_display}" "${_ci_ds_bind}"
      fi
      ;;
    covered)
      printf 'socket %s (a wildcard socket that serves the configured bind %s)' "${_ci_ds_display}" "${_ci_ds_bind}"
      ;;
    sibling)
      printf 'socket %s (the OTHER address family on the same port: reported, but not the listener of this configuration and not an ownership basis)' "${_ci_ds_display}"
      ;;
    *)
      printf 'socket %s (bound to another address, so it cannot be the listener of this configuration)' "${_ci_ds_display}"
      ;;
  esac
}

ci_describe_bind() {
  # $1 = bind; one sentence about the configured bind and where it is probed.
  _ci_db_bind="$(ci_addr_normalize "${1:-}")"
  _ci_db_hosts="$(ci_addr_probe_hosts "${_ci_db_bind}" | tr '\n' ' ')"
  _ci_db_hosts="${_ci_db_hosts% }"
  if ci_addr_is_wildcard "${_ci_db_bind}"; then
    printf 'wildcard bind, probed at %s' "$(printf '%s' "${_ci_db_hosts}" | sed 's/ /, then /g')"
  elif [ "${_ci_db_bind}" = "localhost" ]; then
    printf 'loopback name, probed at %s' "$(printf '%s' "${_ci_db_hosts}" | sed 's/ /, then /g')"
  elif ci_addr_is_loopback "${_ci_db_bind}"; then
    printf 'loopback'
  else
    printf 'not loopback, so plain HTTP is refused unless web.allowInsecureHttp=true is set deliberately'
  fi
}

