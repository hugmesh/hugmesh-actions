#!/usr/bin/env bash
# casdoor-seed.sh — hugmesh-actions' OWN Casdoor seeder.
#
# Decision 62 (operator, 2026-09-18, hugmesh-actions#7): "Its own
# Casdoor seeder in hugmesh-actions" — NOT hugmesh-generator's
# tests/stack/seed-e2e.sh, which targets a different topology (one
# Casdoor behind a :8088 front door; this repo runs two standalone
# Casdoors on :8001/:8002 with no front door and no user-bff).
#
# Provisions the SYSTEMS Casdoor (CASDOOR_PLATFORM_URL) with everything
# per-role-access.feature needs — the org, the five bare role objects
# the JWT->authority converter (CasdoorAuthorities, hugmesh-commons)
# upper-cases and ROLE_-prefixes, the hugmesh-actions OAuth app, and one
# test user per role tier — then mints a real password-grant bearer
# token for each and writes e2e/.tokens.json.
#
# SCOPE. This seeds ONLY what issue #7 / Decision 62 is about: the five
# per-role-access-matrix tokens, systems Casdoor only. The GAMES Casdoor
# side (cross-stack-admin-sync.feature needs its OWN 'game-master'/
# 'admin' role objects on both Casdoors, seeded per-scenario today by
# helpers/casdoor.ts against whatever org/app already exists) is a
# SEPARATE, still-open gap that this change does not attempt to close —
# see the note this seeder's caller leaves in bring-up-stack.sh.
#
# ADMIN SESSION. Every provisioning call below authenticates with the
# casbin/casdoor FRESH-BOOT default admin session (org "built-in", user
# "admin", password "123" — override with CASDOOR_ADMIN_USER /
# CASDOOR_ADMIN_PASS), the SAME convention hugmesh-generator's
# tests/stack/seed-e2e.sh already uses against this identical image. It
# is a throwaway local Casdoor bound to a published localhost port with
# a throwaway compose volume; the cluster's Casdoor never uses this
# default (its init-job rotates it via Vault/ESO and PROVES "123" no
# longer logs in — see tests/scripts/test-casdoor-init-honesty.sh in
# hugmesh-generator).
#
# IDEMPOTENT. Every create step probes before creating (matches
# seed-e2e.sh's ensure_* convention) — safe to re-run against a Casdoor
# that already holds this seeder's state.
#
# FAIL-CLOSED, ALWAYS. Any unmet step returns 1 immediately from
# provision_and_mint_tokens, which writes NOTHING to the tokens path —
# the caller (lib/token-mint-guard.sh) decides what goes there for the
# fail-closed / anonymous-only paths. This function never writes a
# partial token map (3 of 5 keys) and calls it success.

# shellcheck source=./e2e-password-resolve.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/e2e-password-resolve.sh"

: "${CASDOOR_ADMIN_USER:=admin}"
: "${CASDOOR_ADMIN_PASS:=123}"

# The five auth-matrix role tiers -> the ONE Casdoor test user each is
# granted, and which role(s) that user holds, and which e2e/.tokens.json
# KEY its minted token is filed under. Format:
#   <username>:<CSV of Casdoor roles to GRANT>:<tokens.json key>
#
# "actions-admin" is GRANTED both ADMIN and GAME-MASTER in Casdoor:
# per-role-access.feature's admin scenario asserts GET
# /actuator/metrics -> 200, and SecurityConfig gates /actuator/** on
# hasRole("GAME-MASTER") specifically (not ADMIN), so an admin-only
# token would 403 there. But its token is filed ONLY under the ADMIN
# key — the GAME-MASTER key's token must come from actions-game-master
# (who holds ONLY GAME-MASTER), or the game-master-tier scenario's
# "cannot reach registration" assertion would silently pass against an
# admin-capable token instead of the game-master-only one it claims to
# be testing. Grant field and token-map field are DELIBERATELY separate
# for exactly this reason — do not collapse them back into one CSV that
# both grants roles AND fans a token out to every key it lists.
_CASDOOR_SEED_SPECS="actions-user:USER:USER actions-producer:PRODUCER:PRODUCER actions-mod:MOD:MOD actions-game-master:GAME-MASTER:GAME-MASTER actions-admin:ADMIN,GAME-MASTER:ADMIN"

_cs_log()  { echo "[casdoor-seed] $*" >&2; }
_cs_fail() { echo "[casdoor-seed] FAILED: $*" >&2; }

_cs_login() { # <base> <jar>
  local base="$1" jar="$2"
  curl -sf --max-time 10 -c "${jar}" "${base}/api/login" \
      -H 'content-type: application/json' \
      -d "$(jq -nc --arg u "${CASDOOR_ADMIN_USER}" --arg p "${CASDOOR_ADMIN_PASS}" \
              '{application:"app-built-in",organization:"built-in",username:$u,password:$p,type:"login",autoSignin:true}')" \
    2>/dev/null | jq -e '.status == "ok"' >/dev/null 2>&1
}

_cs_ensure_org() { # <base> <jar> <org>
  local base="$1" jar="$2" org="$3"
  curl -s -b "${jar}" --max-time 10 "${base}/api/get-organization?id=admin/${org}" 2>/dev/null \
    | jq -e '.data.name // empty | length > 0' >/dev/null 2>&1 && return 0
  curl -s -b "${jar}" --max-time 10 "${base}/api/add-organization" \
      -H 'content-type: application/json' \
      -d "$(jq -nc --arg org "${org}" \
              '{owner:"admin",name:$org,displayName:$org,passwordType:"plain",tags:[],languages:[],initScore:2000,enableSoftDeletion:false,isProfilePublic:true}')" \
    >/dev/null 2>&1
  curl -s -b "${jar}" --max-time 10 "${base}/api/get-organization?id=admin/${org}" 2>/dev/null \
    | jq -e '.data.name // empty | length > 0' >/dev/null 2>&1
}

_cs_ensure_role() { # <base> <jar> <org> <role>
  local base="$1" jar="$2" org="$3" role="$4"
  curl -s -b "${jar}" --max-time 10 "${base}/api/get-role?id=${org}/${role}" 2>/dev/null \
    | jq -e '.data.name // empty | length > 0' >/dev/null 2>&1 && return 0
  curl -s -b "${jar}" --max-time 10 "${base}/api/add-role" \
      -H 'content-type: application/json' \
      -d "$(jq -nc --arg org "${org}" --arg role "${role}" \
              '{owner:$org,name:$role,displayName:$role,users:[],roles:[],isEnabled:true}')" \
    >/dev/null 2>&1
  curl -s -b "${jar}" --max-time 10 "${base}/api/get-role?id=${org}/${role}" 2>/dev/null \
    | jq -e '.data.name // empty | length > 0' >/dev/null 2>&1
}

_cs_ensure_app() { # <base> <jar> <org> <client_id> <client_secret>
  local base="$1" jar="$2" org="$3" cid="$4" secret="$5" app
  app="$(jq -nc --arg org "${org}" --arg cid "${cid}" --arg secret "${secret}" \
          '{owner:"admin",name:$cid,displayName:"hugmesh-actions e2e",organization:$org,
            clientId:$cid,clientSecret:$secret,redirectUris:[],
            grantTypes:["password","client_credentials","refresh_token"],
            expireInHours:24,enablePassword:true,enableSignUp:false,
            tokenFormat:"JWT-Custom",tokenSigningMethod:"RS256",
            tokenFields:["Id","Name","Email"],
            tokenAttributes:[
              {name:"roles",category:"Existing Field",value:"Roles",type:"Array"},
              {name:"permissions",category:"Existing Field",value:"Permissions",type:"Array"}
            ]}')"
  if curl -s -b "${jar}" --max-time 10 "${base}/api/get-application?id=admin/${cid}" 2>/dev/null \
       | jq -e '.data.name // empty | length > 0' >/dev/null 2>&1; then
    curl -s -b "${jar}" --max-time 10 "${base}/api/update-application?id=admin/${cid}" \
        -H 'content-type: application/json' -d "${app}" >/dev/null 2>&1
  else
    curl -s -b "${jar}" --max-time 10 "${base}/api/add-application" \
        -H 'content-type: application/json' -d "${app}" >/dev/null 2>&1
  fi
  # Verify EXISTENCE only (matches the proven pattern hugmesh-generator's
  # seed-e2e.sh ensure_app uses) — NOT that the read-back clientSecret
  # matches what we posted. Never proven against a real Casdoor in this
  # session whether an admin-session get-application echoes the secret
  # verbatim or masks it; the real, end-to-end proof that the secret we
  # posted took effect is _cs_mint_token's password-grant call below,
  # which Casdoor validates the secret against server-side regardless of
  # what a read-back would show.
  curl -s -b "${jar}" --max-time 10 "${base}/api/get-application?id=admin/${cid}" 2>/dev/null \
    | jq -e '.data.name // empty | length > 0' >/dev/null 2>&1
}

_cs_ensure_user() { # <base> <jar> <org> <username> <password>
  local base="$1" jar="$2" org="$3" user="$4" pass="$5"
  curl -s -b "${jar}" --max-time 10 "${base}/api/get-user?id=${org}/${user}" 2>/dev/null \
    | jq -e '.data.name // empty | length > 0' >/dev/null 2>&1 && return 0
  curl -s -b "${jar}" --max-time 10 "${base}/api/add-user" \
      -H 'content-type: application/json' \
      -d "$(jq -nc --arg org "${org}" --arg user "${user}" --arg pass "${pass}" \
              '{owner:$org,name:$user,displayName:$user,password:$pass,type:"normal-user",isForbidden:false}')" \
    >/dev/null 2>&1
  curl -s -b "${jar}" --max-time 10 "${base}/api/get-user?id=${org}/${user}" 2>/dev/null \
    | jq -e '.data.name // empty | length > 0' >/dev/null 2>&1
}

# Idempotent membership add — reads the role's current member list and
# only writes back when the member is absent, mirroring
# helpers/casdoor.ts's addRoleMember(). update-role SETS members to
# exactly the list posted, so a blind overwrite would be wrong if the
# role already held other members from an earlier partial run.
_cs_add_role_member() { # <base> <jar> <org> <role> <username>
  local base="$1" jar="$2" org="$3" role="$4" user="$5"
  local member current updated
  member="${org}/${user}"
  current="$(curl -s -b "${jar}" --max-time 10 "${base}/api/get-role?id=${org}/${role}" 2>/dev/null \
    | jq -c '.data.users // []' 2>/dev/null)"
  [ -n "${current}" ] || current="[]"
  if printf '%s' "${current}" | jq -e --arg m "${member}" 'index($m) != null' >/dev/null 2>&1; then
    return 0
  fi
  updated="$(printf '%s' "${current}" | jq -c --arg m "${member}" '. + [$m]')"
  curl -s -b "${jar}" --max-time 10 "${base}/api/update-role?id=${org}/${role}" \
      -H 'content-type: application/json' \
      -d "$(jq -nc --arg org "${org}" --arg role "${role}" --argjson users "${updated}" \
              '{owner:$org,name:$role,users:$users}')" \
    >/dev/null 2>&1
  current="$(curl -s -b "${jar}" --max-time 10 "${base}/api/get-role?id=${org}/${role}" 2>/dev/null \
    | jq -c '.data.users // []' 2>/dev/null)"
  printf '%s' "${current}" | jq -e --arg m "${member}" 'index($m) != null' >/dev/null 2>&1
}

# Password-grant mint — same shape hugmesh-generator's
# tests/qemu/seed-accounts.sh premint_token() already proved against a
# live Casdoor: plain username first, <org>/<username> only as a
# fallback spelling. Prints the access_token (empty on failure); never
# fails loudly itself — the caller decides.
_cs_mint_token() { # <base> <org> <client_id> <client_secret> <username> <password>
  local base="$1" org="$2" cid="$3" secret="$4" user="$5" pass="$6"
  local body tok
  body="$(curl -s --max-time 10 -X POST "${base}/api/login/oauth/access_token" \
      --data-urlencode "grant_type=password" \
      --data-urlencode "username=${user}" \
      --data-urlencode "password=${pass}" \
      --data-urlencode "client_id=${cid}" \
      --data-urlencode "client_secret=${secret}" 2>/dev/null || true)"
  tok="$(printf '%s' "${body}" | jq -r '.access_token // empty' 2>/dev/null || true)"
  if [ -z "${tok}" ]; then
    body="$(curl -s --max-time 10 -X POST "${base}/api/login/oauth/access_token" \
        --data-urlencode "grant_type=password" \
        --data-urlencode "username=${org}/${user}" \
        --data-urlencode "password=${pass}" \
        --data-urlencode "client_id=${cid}" \
        --data-urlencode "client_secret=${secret}" 2>/dev/null || true)"
    tok="$(printf '%s' "${body}" | jq -r '.access_token // empty' 2>/dev/null || true)"
  fi
  printf '%s' "${tok}"
}

# provision_and_mint_tokens <tokens_json_path> — the ONE entry point.
# Returns 0 and writes a real 5-key token map ONLY on full success; on
# ANY failure returns 1 and leaves the tokens path untouched.
provision_and_mint_tokens() {
  local tokens_json_path="${1:?provision_and_mint_tokens: tokens json path required}"

  command -v jq   >/dev/null 2>&1 || { _cs_fail "jq is required"; return 1; }
  command -v curl >/dev/null 2>&1 || { _cs_fail "curl is required"; return 1; }

  local org="${CASDOOR_PLATFORM_ORG:-hugmesh}"
  local cid="${CASDOOR_PLATFORM_CLIENT_ID:-hugmesh-actions}"
  local secret="${CASDOOR_PLATFORM_CLIENT_SECRET:-}"
  # CASDOOR_PLATFORM_URL (default http://casdoor-systems:8000) is what
  # the CONTAINER reaches Casdoor at, and what `origin` in compose.yaml
  # pins every minted token's `iss` to, regardless of which host this
  # script calls through — that pin is the whole point of Decision 62's
  # third prerequisite. This SCRIPT runs on the host, so it calls
  # through the published port instead; override with
  # CASDOOR_PLATFORM_SEED_URL if that ever needs to differ.
  local seed_base="${CASDOOR_PLATFORM_SEED_URL:-http://localhost:8001}"

  if [ -z "${secret}" ]; then
    _cs_fail "CASDOOR_PLATFORM_CLIENT_SECRET is empty — bring-up-stack.sh must generate and export it BEFORE 'docker compose up' (see generate_client_secret in lib/e2e-password-resolve.sh)"
    return 1
  fi

  local jar
  jar="$(mktemp "${TMPDIR:-/tmp}/casdoor-seed-cookies.XXXXXX")" || { _cs_fail "mktemp failed"; return 1; }
  # shellcheck disable=SC2064
  trap "rm -f '${jar}'" RETURN

  _cs_log "logging in to Casdoor admin at ${seed_base}"
  if ! _cs_login "${seed_base}" "${jar}"; then
    _cs_fail "Casdoor admin login at ${seed_base} (CASDOOR_ADMIN_USER/CASDOOR_ADMIN_PASS — the compose default is admin/123 on a FRESH casdoor volume)"
    return 1
  fi

  if ! _cs_ensure_org "${seed_base}" "${jar}" "${org}"; then
    _cs_fail "could not create/verify Casdoor org ${org} at ${seed_base}"
    return 1
  fi

  local role
  for role in USER PRODUCER MOD GAME-MASTER ADMIN; do
    if ! _cs_ensure_role "${seed_base}" "${jar}" "${org}" "${role}"; then
      _cs_fail "could not create/verify Casdoor role ${org}/${role}"
      return 1
    fi
  done

  if ! _cs_ensure_app "${seed_base}" "${jar}" "${org}" "${cid}" "${secret}"; then
    _cs_fail "could not create/verify Casdoor app ${cid} at ${seed_base}"
    return 1
  fi

  local -A minted=()
  local spec username rest roles_csv output_key pass tok r
  local -a role_arr
  for spec in ${_CASDOOR_SEED_SPECS}; do
    username="${spec%%:*}"
    rest="${spec#*:}"
    roles_csv="${rest%%:*}"
    output_key="${rest#*:}"

    pass="$(resolve_e2e_password "${username}")" || return 1

    if ! _cs_ensure_user "${seed_base}" "${jar}" "${org}" "${username}" "${pass}"; then
      _cs_fail "could not create/verify Casdoor user ${org}/${username}"
      return 1
    fi

    role_arr=()
    IFS=',' read -ra role_arr <<< "${roles_csv}"
    for r in "${role_arr[@]}"; do
      if ! _cs_add_role_member "${seed_base}" "${jar}" "${org}" "${r}" "${username}"; then
        _cs_fail "could not grant role ${r} to ${username}"
        return 1
      fi
    done

    tok="$(_cs_mint_token "${seed_base}" "${org}" "${cid}" "${secret}" "${username}" "${pass}")"
    if [ -z "${tok}" ]; then
      _cs_fail "password-grant mint for ${username} returned no access_token"
      return 1
    fi
    minted["${output_key}"]="${tok}"
  done

  for role in USER PRODUCER MOD GAME-MASTER ADMIN; do
    if [ -z "${minted[${role}]:-}" ]; then
      _cs_fail "no token minted for role ${role}"
      return 1
    fi
  done

  if ! jq -n \
      --arg u "${minted[USER]}" \
      --arg p "${minted[PRODUCER]}" \
      --arg m "${minted[MOD]}" \
      --arg g "${minted[GAME-MASTER]}" \
      --arg a "${minted[ADMIN]}" \
      '{"USER":$u,"PRODUCER":$p,"MOD":$m,"GAME-MASTER":$g,"ADMIN":$a}' \
      > "${tokens_json_path}"; then
    _cs_fail "could not write ${tokens_json_path}"
    return 1
  fi

  _cs_log "minted 5/5 per-role bearer tokens into ${tokens_json_path}"
  return 0
}
