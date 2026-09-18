#!/usr/bin/env bash
# Shared by bring-up-stack.sh and its test (../test-token-mint-guard.sh).
# Split out so the fail/allow decision below can be exercised WITHOUT a
# live Docker stack.
#
# Real per-role bearer-token minting is still DEFERRED — see the
# DEFERRED(e2e-tokens) block in bring-up-stack.sh for the three measured,
# unmet prerequisites and the seeder-architecture decision nobody has made
# yet. This function is what stands in for it today: it writes
# e2e/.tokens.json as an empty stub (never a real success), and then:
#
#   - by default, FAILS LOUDLY — prints an ERROR banner to stderr and
#     returns 1, so bring-up-stack.sh exits non-zero rather than reporting
#     "✓ Stack up" over a token file with no tokens in it;
#   - only if the caller sets E2E_ALLOW_ANONYMOUS_ONLY=1 — an explicit,
#     opt-in acknowledgement — does it return 0, and even then the banner
#     is a WARNING (⚠), never a plain ✓, because every authenticated
#     scenario still fails at the "is authenticated" step.
#
# Args: $1 = path to write the (empty) tokens JSON stub to.
mint_tokens_or_fail() {
  local tokens_json_path="${1:?mint_tokens_or_fail: tokens json path required}"

  echo '{}' > "$tokens_json_path"

  if [ "${E2E_ALLOW_ANONYMOUS_ONLY:-}" = "1" ]; then
    echo "⚠ Per-role bearer tokens were NOT minted — $tokens_json_path is an" >&2
    echo "  empty stub. Continuing ANONYMOUS-ONLY because" >&2
    echo "  E2E_ALLOW_ANONYMOUS_ONLY=1 is set. Every authenticated scenario" >&2
    echo "  will still fail at the 'is authenticated' step unless you export" >&2
    echo "  TOKEN_USER / TOKEN_PRODUCER / TOKEN_MOD / TOKEN_GAME_MASTER /" >&2
    echo "  TOKEN_ADMIN yourself. See the DEFERRED(e2e-tokens) block in" >&2
    echo "  bring-up-stack.sh for the three measured prerequisites." >&2
    return 0
  fi

  echo "✗ Per-role bearer tokens were NOT minted — $tokens_json_path is an" >&2
  echo "  empty stub, and this is a FAILURE, not a stack you can run the" >&2
  echo "  authenticated suite against. Every authenticated scenario would" >&2
  echo "  fail at the 'is authenticated' step." >&2
  echo "  - To run the ANONYMOUS-only scenarios anyway, re-run with" >&2
  echo "    E2E_ALLOW_ANONYMOUS_ONLY=1." >&2
  echo "  - To run authenticated scenarios today, export TOKEN_USER /" >&2
  echo "    TOKEN_PRODUCER / TOKEN_MOD / TOKEN_GAME_MASTER / TOKEN_ADMIN" >&2
  echo "    yourself before running the suite (bring-up-stack.sh still" >&2
  echo "    won't write them for you)." >&2
  echo "  See the DEFERRED(e2e-tokens) block in bring-up-stack.sh for the" >&2
  echo "  three measured prerequisites blocking a real mint." >&2
  return 1
}
