#!/usr/bin/env bash
# Shared by bring-up-stack.sh and its test (../test-token-mint-guard.sh).
# Split out so the fail/allow decision below can be exercised WITHOUT a
# live Docker stack.
#
# Decision 62 (operator, 2026-09-18, hugmesh-actions#7): this repo grows
# its OWN Casdoor seeder (lib/casdoor-seed.sh) rather than consuming
# hugmesh-generator's seed-e2e.sh. mint_tokens_or_fail is the fail/allow
# decision AROUND that seeder:
#
#   - E2E_ALLOW_ANONYMOUS_ONLY=1 — an explicit, opt-in escape hatch —
#     skips the seeder entirely, writes e2e/.tokens.json as an empty
#     stub, and returns 0 with a WARNING (⚠) banner, never a plain ✓,
#     because every authenticated scenario still fails at the "is
#     authenticated" step. Use this when you deliberately want the
#     anonymous-only scenarios without a Casdoor provisioning pass
#     (e.g. no live Casdoor to seed yet).
#   - otherwise, it calls provision_and_mint_tokens (lib/casdoor-seed.sh)
#     for real:
#       * on full success — org, roles, app and all five per-role users
#         provisioned AND all five tokens minted — it writes the real
#         token map and returns 0 with a plain ✓ banner.
#       * on ANY failure it OVERWRITES the tokens path with an empty
#         stub (never a partial map — 3 of 5 keys is not a success),
#         prints an ERROR (✗) banner naming what the seeder reported,
#         and returns 1, so bring-up-stack.sh exits non-zero rather
#         than reporting "✓ Stack up" over tokens that were never
#         minted or only partially minted.
#
# Args: $1 = path to write the tokens JSON to.

# shellcheck source=./casdoor-seed.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/casdoor-seed.sh"

mint_tokens_or_fail() {
  local tokens_json_path="${1:?mint_tokens_or_fail: tokens json path required}"

  if [ "${E2E_ALLOW_ANONYMOUS_ONLY:-}" = "1" ]; then
    echo '{}' > "$tokens_json_path"
    echo "⚠ Per-role bearer tokens were NOT minted — $tokens_json_path is an" >&2
    echo "  empty stub. Continuing ANONYMOUS-ONLY because" >&2
    echo "  E2E_ALLOW_ANONYMOUS_ONLY=1 is set — the seeder was not run." >&2
    echo "  Every authenticated scenario will still fail at the 'is" >&2
    echo "  authenticated' step unless you export TOKEN_USER /" >&2
    echo "  TOKEN_PRODUCER / TOKEN_MOD / TOKEN_GAME_MASTER / TOKEN_ADMIN" >&2
    echo "  yourself." >&2
    return 0
  fi

  if provision_and_mint_tokens "$tokens_json_path"; then
    echo "✓ Per-role bearer tokens minted into $tokens_json_path (org" >&2
    echo "  ${CASDOOR_PLATFORM_ORG:-hugmesh}, app ${CASDOOR_PLATFORM_CLIENT_ID:-hugmesh-actions})." >&2
    return 0
  fi

  echo '{}' > "$tokens_json_path"
  echo "✗ Per-role bearer tokens were NOT minted — $tokens_json_path is an" >&2
  echo "  empty stub, and this is a FAILURE, not a stack you can run the" >&2
  echo "  authenticated suite against. See the [casdoor-seed] FAILED line" >&2
  echo "  above for which step did not complete. Every authenticated" >&2
  echo "  scenario would fail at the 'is authenticated' step." >&2
  echo "  - To run the ANONYMOUS-only scenarios anyway, re-run with" >&2
  echo "    E2E_ALLOW_ANONYMOUS_ONLY=1 (skips the seeder entirely)." >&2
  echo "  - To run authenticated scenarios today, export TOKEN_USER /" >&2
  echo "    TOKEN_PRODUCER / TOKEN_MOD / TOKEN_GAME_MASTER / TOKEN_ADMIN" >&2
  echo "    yourself before running the suite." >&2
  return 1
}
