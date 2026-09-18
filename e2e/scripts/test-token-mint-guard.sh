#!/usr/bin/env bash
# Unit test for lib/token-mint-guard.sh — the fail/allow decision
# bring-up-stack.sh makes once the compose stack is healthy but no real
# per-role bearer tokens can be minted (issue hugmesh-actions#7).
#
# Runs entirely offline: no docker, no network, no live Casdoor. Sources
# the guard directly and calls mint_tokens_or_fail() against a scratch
# file, asserting on its exit status, stderr banner and the tokens file
# it writes.
#
# Usage: bash e2e/scripts/test-token-mint-guard.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/token-mint-guard.sh
source "$here/lib/token-mint-guard.sh"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/token-mint-guard-test.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

pass_count=0
check() {
  local desc="$1"
  pass_count=$((pass_count + 1))
  echo "ok - $desc"
}

# ── Test 1: default (no E2E_ALLOW_ANONYMOUS_ONLY) fails loudly ────────────
tokens_file="$work_dir/tokens1.json"
unset E2E_ALLOW_ANONYMOUS_ONLY || true
set +e
stderr_out="$(mint_tokens_or_fail "$tokens_file" 2>&1 1>/dev/null)"
rc=$?
set -e

[ "$rc" -ne 0 ] || fail "default path: expected non-zero exit, got $rc"
[ -f "$tokens_file" ] || fail "default path: tokens file was not written"
[ "$(cat "$tokens_file")" = "{}" ] || fail "default path: tokens file is not an empty object"
case "$stderr_out" in
  *"✗"*"NOT minted"*) : ;;
  *) fail "default path: stderr did not contain the ✗ failure banner: $stderr_out" ;;
esac
case "$stderr_out" in
  *"✓"*) fail "default path: stderr must never contain a plain ✓ success mark: $stderr_out" ;;
esac
check "default (no opt-in) returns non-zero and prints a ✗ banner, never ✓"

# ── Test 2: E2E_ALLOW_ANONYMOUS_ONLY=1 returns 0 but still warns ──────────
tokens_file="$work_dir/tokens2.json"
export E2E_ALLOW_ANONYMOUS_ONLY=1
set +e
stderr_out="$(mint_tokens_or_fail "$tokens_file" 2>&1 1>/dev/null)"
rc=$?
set -e
unset E2E_ALLOW_ANONYMOUS_ONLY

[ "$rc" -eq 0 ] || fail "opt-in path: expected exit 0, got $rc"
[ -f "$tokens_file" ] || fail "opt-in path: tokens file was not written"
[ "$(cat "$tokens_file")" = "{}" ] || fail "opt-in path: tokens file is not an empty object"
case "$stderr_out" in
  *"⚠"*"NOT minted"*) : ;;
  *) fail "opt-in path: stderr did not contain the ⚠ warning banner: $stderr_out" ;;
esac
case "$stderr_out" in
  *"✓"*) fail "opt-in path: stderr must never contain a plain ✓ success mark: $stderr_out" ;;
esac
check "E2E_ALLOW_ANONYMOUS_ONLY=1 returns 0 and prints a ⚠ banner, never ✓"

# ── Test 3: any other value of the var is treated as NOT opted in ─────────
tokens_file="$work_dir/tokens3.json"
export E2E_ALLOW_ANONYMOUS_ONLY=yes
set +e
mint_tokens_or_fail "$tokens_file" >/dev/null 2>&1
rc=$?
set -e
unset E2E_ALLOW_ANONYMOUS_ONLY
[ "$rc" -ne 0 ] || fail "E2E_ALLOW_ANONYMOUS_ONLY=yes (not \"1\") must still fail loudly, got exit 0"
check "E2E_ALLOW_ANONYMOUS_ONLY set to a non-\"1\" value still fails loudly"

# ── Test 4: bring-up-stack.sh actually calls the guard and fails closed ───
main_script="$here/bring-up-stack.sh"
bash -n "$main_script" || fail "bring-up-stack.sh has a syntax error"
grep -q 'source "$(dirname "$0")/lib/token-mint-guard.sh"' "$main_script" \
  || fail "bring-up-stack.sh no longer sources lib/token-mint-guard.sh"
grep -q 'if mint_tokens_or_fail e2e/.tokens.json; then' "$main_script" \
  || fail "bring-up-stack.sh no longer gates on mint_tokens_or_fail"
grep -q '^  exit 1$' "$main_script" \
  || fail "bring-up-stack.sh's else-branch no longer exits non-zero"
check "bring-up-stack.sh sources the guard, gates on it, and exits 1 on failure"

# ── Test 5: the old TODO(e2e-tokens) marker spelling is gone from source ──
# Repo-wide, not just bring-up-stack.sh: PR hugmesh-actions#8's own review
# caught the same literal string surviving untouched in root README.md while
# the script and e2e/README.md had already been renamed. Sweep every
# git-tracked file (git grep -I skips binaries, so it can't false-fail open
# on those) except this test script, which necessarily quotes the retired
# spelling to describe what it is checking for.
repo_root="$(cd "$here/../.." && pwd)"
set +e
todo_hits="$(git -C "$repo_root" grep -n -I 'TODO(e2e-tokens)' -- . \
  ':!e2e/scripts/test-token-mint-guard.sh' 2>/dev/null)"
set -e
[ -z "$todo_hits" ] || fail "TODO(e2e-tokens) marker still present in the tree: $todo_hits"
check "TODO(e2e-tokens) marker spelling is gone from the whole repo, not just bring-up-stack.sh"

echo "All $pass_count checks passed."
