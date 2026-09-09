#!/usr/bin/env bash
# Bring up the cross-stack test environment defined by the repo-root
# compose.yaml, and wait for it to report healthy:
#   • casdoor-systems on :8001
#   • casdoor-games   on :8002
#   • hugmesh-actions on :8090
#
# WHAT THIS SCRIPT DOES NOT DO. This header previously advertised four
# things that are not wired. Each is measured; each names where the
# evidence lives. Read this before trusting the stack it brings up:
#
#   • NEITHER CASDOOR IS POSTGRES-BACKED. Both run the image's MySQL
#     default, so the pg-systems / pg-games containers they `depends_on`
#     hold no Casdoor data. -> TODO(e2e-tokens) below, item 1.
#   • NOTHING SEEDS THE hugmesh-actions ADMIN CLIENT. No org `hugmesh`,
#     no roles, no OAuth app is provisioned; a fresh Casdoor holds only
#     `built-in` / `app-built-in`. -> TODO(e2e-tokens) below, item 2.
#   • NO PER-ROLE BEARER TOKENS ARE MINTED. e2e/.tokens.json is written
#     as an empty `{}` stub, so every authenticated scenario fails at the
#     `is authenticated` step unless you export TOKEN_USER / TOKEN_PRODUCER
#     / TOKEN_MOD / TOKEN_GAME_MASTER / TOKEN_ADMIN yourself.
#     -> TODO(e2e-tokens) below.
#   • hugmesh-actions DOES NOT RUN WITH GM_SYNC_INTERVAL_MS=2000. There
#     is no e2e compose overlay and compose.yaml sets no override, so the
#     service runs application.yml's `${GM_SYNC_INTERVAL_MS:60000}` default
#     of 60 s. -> "Known Gaps / TODO" in e2e/README.md.
#
# Usage:
#   bash e2e/scripts/bring-up-stack.sh    # bring up + wait healthy
#   bash e2e/scripts/bring-up-stack.sh stop   # tear down
#
# The script idempotently re-creates state — safe to re-run while
# iterating on a scenario.

set -euo pipefail

cd "$(dirname "$0")/../.."  # repo root

case "${1:-up}" in
  stop|down)
    docker compose -f compose.yaml down -v
    rm -f e2e/.tokens.json
    exit 0
    ;;
esac

# The repo-root compose.yaml, as-is. There is NO e2e overlay: nothing
# shortens the sync tick and nothing binds tokens to known values. See the
# header for what that costs you.
docker compose -f compose.yaml up -d --wait

echo "→ Waiting for both Casdoors..."
for url in http://localhost:8001/api/health http://localhost:8002/api/health; do
  for i in $(seq 1 60); do
    curl -sf "$url" >/dev/null && break
    sleep 2
    if [ "$i" -eq 60 ]; then
      echo "Casdoor at $url not healthy after 120s" >&2
      exit 1
    fi
  done
done

echo "→ Waiting for hugmesh-actions..."
for i in $(seq 1 60); do
  curl -sf http://localhost:8090/actuator/health >/dev/null && break
  sleep 2
  if [ "$i" -eq 60 ]; then
    echo "hugmesh-actions not healthy after 120s" >&2
    docker compose -f compose.yaml logs hugmesh-actions | tail -50
    exit 1
  fi
done

# ── Per-role bearer tokens ─────────────────────────────────────────────────
# e2e tests need bearer tokens for ROLE_USER / ROLE_PRODUCER / ROLE_MOD /
# ROLE_GAME-MASTER / ROLE_ADMIN against the systems Casdoor.
#
# SERVER-SIDE ONLY. This is test SETUP — a server-to-server Bearer JWT
# acquisition, NOT a browser login. It is deliberately allowed to hit
# Casdoor directly (OAuth2 password grant / Casdoor /api/login token
# endpoint) because no browser is involved and the resulting JWT is used
# only to call the actions API directly from the auth steps. It never
# renders Casdoor's hosted login form.
#
# BROWSER logins (when a scenario drives a real page) go through OUR
# login-ui native form instead — POST /bff/login via be-gateway — see
# e2e/features/steps/helpers/login-ui.ts. Casdoor's hosted login page is
# never used anywhere in this suite.
#
# TODO(e2e-tokens): mint the five per-role bearer JWTs here and write them to
# e2e/.tokens.json, keyed by the EXACT role strings the .feature files pass to
# `Given "<actor>" is authenticated with role "<ROLE>"` — USER / PRODUCER /
# MOD / GAME-MASTER / ADMIN (tokenFor() in features/steps/auth-steps.ts does no
# case or format translation on that lookup).
#
# STILL A STUB, and the missing piece is NOT just the token call. Measured
# 2026-09-09 against casbin/casdoor:3.80.0 (conf/app.conf extracted from the
# image) plus this repo's compose.yaml, THREE prerequisites are unmet:
#
#   1. NEITHER CASDOOR IS WIRED TO ITS POSTGRES. The image default is
#      `driverName = mysql` with
#      `dataSourceName = root:123456@tcp(localhost:3306)/`, and compose.yaml
#      sets neither `driverName` nor `dataSourceName` on casdoor-systems /
#      casdoor-games — so both target MySQL on the docker host rather than the
#      pg-systems / pg-games containers they `depends_on`, and compose.yaml's
#      "Postgres-backed" header comment does not describe what is wired. The
#      env pair to add is the one hugmesh-generator already runs against this
#      same image in tests/stack/compose.real.yaml.
#
#   2. NOTHING PROVISIONS THE IDENTITIES THE GRANT NEEDS. The image ships no
#      init_data.json (verified absent) and compose.yaml mounts none, so a
#      fresh Casdoor holds ONLY organization `built-in` and app
#      `app-built-in`. There is no org `hugmesh`, no roles
#      user/producer/mod/game-master/admin, and no OAuth app whose clientId is
#      `hugmesh-actions` — while CASDOOR_PLATFORM_CLIENT_SECRET defaults to
#      EMPTY in compose.yaml. So both the Casdoor admin API (Basic
#      clientId:clientSecret, the auth features/steps/helpers/casdoor.ts
#      already uses) and the password grant have nothing to authenticate as,
#      and no user to mint for. Seeding that is the real body of this task —
#      and it needs a decision first: does this repo grow its OWN Casdoor
#      seeder, or consume hugmesh-generator's tests/stack/seed-e2e.sh? That
#      script targets a DIFFERENT topology (one Casdoor on :8000 behind a
#      front door on :8088; this stack is two Casdoors on :8001/:8002), so it
#      is not reusable as-is. That open choice is why this is not a small
#      change.
#
#   3. EVEN A SUCCESSFUL MINT WOULD BE REJECTED. `origin` is EMPTY in the
#      image default, so Casdoor derives a token's `iss` from the REQUEST
#      HOST. A mint issued by this script (http://localhost:8001) would stamp
#      iss=http://localhost:8001, while hugmesh-actions validates
#      issuer-uri=${CASDOOR_PLATFORM_URL}, i.e. http://casdoor-systems:8000 —
#      so every authenticated scenario would 401 while holding a perfectly
#      good token. Fix: pin `origin` on each Casdoor to the URL the service
#      validates. hugmesh-generator hit exactly this and documented the
#      measurement in tests/stack/compose.real.yaml.
#
# WHEN THE MINT LANDS, two contracts it must honour:
#   * PASSWORD RESOLUTION follows the org e2e-credential contract this repo
#     already consumes on the TypeScript side
#     (features/steps/helpers/e2e-credentials.ts): $E2E_<USER>_PASSWORD ->
#     $E2E_PASSWORD -> the .e2e-credentials run-state file -> THROW. This repo
#     is a CONSUMER: it must never generate or hardcode a password.
#   * GRANT SHAPE: POST <casdoor>/api/login/oauth/access_token,
#     grant_type=password, trying the PLAIN username first and <org>/<name>
#     only as a fallback spelling, reading .access_token and reporting
#     Casdoor's .error code on failure (invalid_grant = credentials/user
#     lookup, unsupported_grant_type = grant config) — the shape
#     hugmesh-generator's tests/qemu/seed-accounts.sh premint_token() already
#     proved against a live Casdoor.
#
# Until then this writes an EMPTY object rather than a token map, and SAYS SO
# instead of announcing a mint it did not perform.
echo '{}' > e2e/.tokens.json
echo "! Per-role bearer tokens were NOT minted — e2e/.tokens.json is an empty"
echo "  stub. Authenticated scenarios fail at the 'is authenticated' step"
echo "  unless you export TOKEN_USER / TOKEN_PRODUCER / TOKEN_MOD /"
echo "  TOKEN_GAME_MASTER / TOKEN_ADMIN yourself. See the TODO(e2e-tokens)"
echo "  block in this script for the three measured prerequisites."

# NOTE: single quotes, not backticks. Backticks inside a double-quoted string are
# COMMAND SUBSTITUTION, so the previous spelling actually RAN the whole Playwright
# suite here and swallowed its output into this banner.
echo "✓ Compose stack up (containers healthy). Run 'npm --prefix e2e test' to"
echo "  execute the suite — without hand-supplied tokens only the anonymous"
echo "  scenarios can pass."
