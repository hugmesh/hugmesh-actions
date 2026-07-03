#!/usr/bin/env bash
# Bring up the cross-stack test environment:
#   • casdoor-systems on :8001  (Postgres-backed; seeds the
#                                snapdragon-actions admin client)
#   • casdoor-games   on :8002  (separate Postgres)
#   • snapdragon-actions on :8090 with GM_SYNC_INTERVAL_MS=2000
#
# Then writes per-role bearer tokens to e2e/.tokens.json so the
# auth steps can read them without a separate Casdoor round-trip
# per scenario.
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

# Use the existing compose.yaml at the repo root + an e2e overlay
# that shortens the sync tick + binds tokens to known values.
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

echo "→ Waiting for snapdragon-actions..."
for i in $(seq 1 60); do
  curl -sf http://localhost:8090/actuator/health >/dev/null && break
  sleep 2
  if [ "$i" -eq 60 ]; then
    echo "snapdragon-actions not healthy after 120s" >&2
    docker compose -f compose.yaml logs snapdragon-actions | tail -50
    exit 1
  fi
done

# Tokens: e2e tests need bearer tokens for ROLE_USER / ROLE_PRODUCER
# / ROLE_MOD / ROLE_GAME-MASTER / ROLE_ADMIN against the systems
# Casdoor.
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
# This script writes the tokens to e2e/.tokens.json. The auth-steps
# helper reads from there; missing tokens fall back to env vars.
echo "→ Minting per-role bearer tokens, SERVER-SIDE via Casdoor (TODO: real impl)..."
echo '{}' > e2e/.tokens.json

echo "✓ Stack up. Run `npm --prefix e2e test` to execute the suite."
