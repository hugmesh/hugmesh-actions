#!/usr/bin/env bash
# e2e-password-resolve.sh — bash port of the org e2e-password CONSUMER
# contract (mirrors e2e/features/steps/helpers/e2e-credentials.ts). This
# repo NEVER generates or hardcodes the shared e2e user password — see
# that file's own header. Precedence:
#
#   1. $E2E_<PERUSER>_PASSWORD  — per-user override, e.g.
#      E2E_ACTIONS_ADMIN_PASSWORD for username "actions-admin".
#   2. $E2E_PASSWORD            — shared override / same-run
#                                  propagation from whatever process
#                                  resolved or generated it first.
#   3. the run-state credentials file — $E2E_CREDENTIALS_FILE if set,
#      else the nearest .e2e-credentials walking UP from $REPO_ROOT
#      (plain KEY=value lines, no quoting — the format
#      e2e_password_ensure()/`make stack-seed` writes).
#   4. FAIL. resolve_e2e_password returns 1 and prints nothing to
#      stdout — never a literal default. The caller decides whether
#      that is fatal.
#
# Sourced by lib/casdoor-seed.sh. Everything here is READ-ONLY: it
# resolves state, it never writes .e2e-credentials — that file is
# hugmesh-generator's tests/stack/e2e-password.sh's job.
#
# ALSO HOME TO generate_client_secret() — a machine (not human) credential
# this seeder IS responsible for minting itself. It is NOT governed by
# the "never invent a password" rule above: that rule protects the one
# shared e2e test-user password from drifting into N per-repo literals;
# a per-run OAuth client secret is scoped to this repo's own throwaway
# Casdoor app and has no cross-repo consumer to stay in sync with.

# env-safe token: "actions-game-master" -> "ACTIONS_GAME_MASTER"
# (collapses runs of non-alnum into ONE underscore, matching the TS
# helper's /[^a-zA-Z0-9]+/g — NOT `tr -c ... '_'`, which maps each
# offending char individually and would turn a double separator into
# two underscores instead of one).
_e2e_env_token() {
  printf '%s' "$1" | tr '[:lower:]' '[:upper:]' | sed -E 's/[^A-Z0-9]+/_/g'
}

# Find the nearest .e2e-credentials walking UP from directory $1.
# Prints the path and returns 0 on a match; returns 1 with nothing
# printed at the filesystem root.
_find_credentials_file_upwards() {
  local start_dir="$1"
  local dir parent
  dir="$(cd "${start_dir}" 2>/dev/null && pwd)" || return 1
  while :; do
    if [ -f "${dir}/.e2e-credentials" ]; then
      printf '%s' "${dir}/.e2e-credentials"
      return 0
    fi
    parent="$(dirname "${dir}")"
    [ "${parent}" = "${dir}" ] && return 1
    dir="${parent}"
  done
}

# Prints nothing and returns 1 when no file is configured/found — that
# is NOT an error at this layer, callers fall through to the next
# precedence step.
_credentials_file_path() {
  if [ -n "${E2E_CREDENTIALS_FILE:-}" ]; then
    printf '%s' "${E2E_CREDENTIALS_FILE}"
    return 0
  fi
  _find_credentials_file_upwards "${REPO_ROOT:-.}"
}

# Read a single KEY=value line from a credentials file. Plain format —
# no quoting, no `export` keyword. A missing/unreadable file or a
# missing key is NOT an error: prints nothing, returns 1.
_read_credentials_key() {
  local file="$1" key="$2" line
  [ -r "${file}" ] || return 1
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      "${key}="*)
        printf '%s' "${line#"${key}"=}"
        return 0
        ;;
    esac
  done < "${file}"
  return 1
}

# resolve_e2e_password [username] -> prints the password on stdout and
# returns 0 on success; on failure prints NOTHING to stdout, an
# actionable message to stderr, and returns 1. Mirrors
# resolveE2ePassword() in helpers/e2e-credentials.ts exactly.
resolve_e2e_password() {
  local username="${1:-}"
  local per_user_key="" val="" file_path=""

  if [ -n "${username}" ]; then
    per_user_key="E2E_$(_e2e_env_token "${username}")_PASSWORD"
    val="$(printenv "${per_user_key}" 2>/dev/null || true)"
    if [ -n "${val}" ]; then
      printf '%s' "${val}"
      return 0
    fi
  fi

  if [ -n "${E2E_PASSWORD:-}" ]; then
    printf '%s' "${E2E_PASSWORD}"
    return 0
  fi

  file_path="$(_credentials_file_path || true)"
  if [ -n "${file_path}" ]; then
    if [ -n "${per_user_key}" ]; then
      val="$(_read_credentials_key "${file_path}" "${per_user_key}" || true)"
      if [ -n "${val}" ]; then
        printf '%s' "${val}"
        return 0
      fi
    fi
    val="$(_read_credentials_key "${file_path}" "E2E_PASSWORD" || true)"
    if [ -n "${val}" ]; then
      printf '%s' "${val}"
      return 0
    fi
  fi

  {
    if [ -n "${username}" ]; then
      echo "resolve_e2e_password: no e2e password resolved for user \"${username}\"."
    else
      echo "resolve_e2e_password: no e2e password resolved."
    fi
    if [ -n "${per_user_key}" ]; then
      echo "  Checked \$${per_user_key}, \$E2E_PASSWORD, and ${file_path:-"<no .e2e-credentials found above ${REPO_ROOT:-.}>"}."
    else
      echo "  Checked \$E2E_PASSWORD, and ${file_path:-"<no .e2e-credentials found above ${REPO_ROOT:-.}>"}."
    fi
    echo "  This repo is a CONSUMER of the org e2e-password contract — it never"
    echo "  generates or hardcodes one. Set \$E2E_PASSWORD explicitly, or run"
    echo "  whatever produced this run's .e2e-credentials (hugmesh-generator's"
    echo "  'make stack-seed')."
  } >&2
  return 1
}

# generate_client_secret <nchars> — a fresh, per-run MACHINE credential
# for the Casdoor OAuth app this seeder provisions
# (CASDOOR_PLATFORM_CLIENT_SECRET / CASDOOR_GAMES_CLIENT_SECRET). See
# the file header for why this one IS allowed to invent a value.
#
# FILTER FIRST, THEN TRUNCATE. `head -c N /dev/urandom | tr -dc
# 'A-Za-z0-9'` truncates to N raw BYTES first and only then discards
# the ~76% of them that are not alphanumeric — for N=32 it returns
# 9-11 characters, not 32, and an unlucky draw can return empty. See
# project_hugmesh_e2e_password_contract.md, trap #1 (the exact bug that
# nearly shipped in the org-wide password generator).
#
# `set +o pipefail` INSIDE the command-substitution subshell, not
# around the call site. `tr -dc ... < /dev/urandom` is an infinite
# producer; `head -c N` reads exactly N bytes and closes its end of the
# pipe while `tr` is almost always still mid-write, so `tr` reliably
# dies of SIGPIPE (rc 141) — under this repo's `set -euo pipefail`
# (bring-up-stack.sh sources this file directly), pipefail promotes
# that 141 to the WHOLE PIPELINE's exit status even though `head`
# itself exited 0, and `set -e` then kills the calling script. Measured
# in this repo's own e2e/scripts/test-casdoor-seed.sh: the naive
# one-liner crashes the test run with exit 141 on the very first call.
# Scoping `+o pipefail` to the subshell leaves the CALLER's pipefail
# setting untouched and makes the substitution's exit status `head`'s
# alone (0 on success), which is the only one that should matter here.
generate_client_secret() {
  local n="${1:-32}"
  local out
  out="$(set +o pipefail; tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "${n}")"
  printf '%s' "${out}"
}
