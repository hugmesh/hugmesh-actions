#!/usr/bin/env bash
# Unit tests for lib/e2e-password-resolve.sh and lib/casdoor-seed.sh —
# the real Casdoor seeder Decision 62 (operator, 2026-09-18,
# hugmesh-actions#7) replaced the token-mint stub with.
#
# Runs entirely offline: no live Casdoor, no Docker. Password
# resolution is exercised directly. provision_and_mint_tokens is
# exercised against a FAKE curl (written to a scratch bin dir and
# PATH-prepended for this test's duration only — never installed
# anywhere else) that simulates just enough of Casdoor's admin API +
# OAuth2 password grant, backed by a tiny file-per-fact state store, to
# prove the SEEDER's own control flow: create-before-mint ordering,
# idempotent re-create, and — the regression this file exists to pin —
# that each of the five tokens.json keys is backed by ITS OWN persona's
# token, never a later persona's token that happens to share a granted
# Casdoor role.
#
# Usage: bash e2e/scripts/test-casdoor-seed.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "${here}/../.." && pwd)"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/casdoor-seed-test.XXXXXX")"
trap 'rm -rf "${work_dir}"' EXIT

fail() { echo "FAIL: $1" >&2; exit 1; }
pass_count=0
check() { pass_count=$((pass_count + 1)); echo "ok - $1"; }

# ── Fake curl, scoped to THIS test only ─────────────────────────────────────
# Deliberately smaller than a real Casdoor: just enough routes for
# login/org/role/app/user CRUD + the password grant, state-backed under
# $SHIM_STATE_DIR so idempotency and role-membership checks are real.
shim_bin="${work_dir}/bin"
mkdir -p "${shim_bin}"
cat > "${shim_bin}/curl" <<'CURL_SHIM'
#!/usr/bin/env bash
set -euo pipefail
STATE_DIR="${SHIM_STATE_DIR:?SHIM_STATE_DIR required}"
mkdir -p "${STATE_DIR}"
url="" body=""
formfields=()
args=("$@")
n=${#args[@]}
i=0
while [ "${i}" -lt "${n}" ]; do
  a="${args[${i}]}"
  case "${a}" in
    http*) url="${a}" ;;
    -d) i=$((i+1)); body="${args[${i}]}" ;;
    --data-urlencode) i=$((i+1)); formfields+=("${args[${i}]}") ;;
    -X|-c|-b|--max-time|-H) i=$((i+1)) ;;
  esac
  i=$((i+1))
done
[ -n "${url}" ] || { echo "SHIM curl: no URL in: $*" >&2; exit 7; }
no_scheme="${url#http://}"
path="/${no_scheme#*/}"
route="${path%%\?*}"
idval=""
case "${path}" in *'?id='*) idval="${path#*'?id='}" ;; esac
case "${route}" in
  /api/health|/actuator/health) echo '{"status":"ok"}' ;;
  /api/login) echo '{"status":"ok"}' ;;
  /api/get-organization)
    org="${idval#*/}"
    if [ -f "${STATE_DIR}/org-${org}" ]; then jq -nc --arg n "${org}" '{data:{name:$n}}'
    else echo '{"data":null}'; fi ;;
  /api/add-organization)
    org="$(printf '%s' "${body}" | jq -r '.name')"; : > "${STATE_DIR}/org-${org}"
    echo '{"status":"ok"}' ;;
  /api/get-role)
    org="${idval%%/*}"; role="${idval#*/}"; f="${STATE_DIR}/role-${org}-${role}"
    if [ -f "${f}" ]; then
      jq -nc --arg n "${role}" --argjson u "$(cat "${f}")" '{data:{name:$n,users:$u}}'
    else echo '{"data":null}'; fi ;;
  /api/add-role)
    org="$(printf '%s' "${body}" | jq -r '.owner')"
    role="$(printf '%s' "${body}" | jq -r '.name')"
    f="${STATE_DIR}/role-${org}-${role}"
    [ -f "${f}" ] || echo '[]' > "${f}"
    echo '{"status":"ok"}' ;;
  /api/update-role)
    org="$(printf '%s' "${body}" | jq -r '.owner')"
    role="$(printf '%s' "${body}" | jq -r '.name')"
    printf '%s' "${body}" | jq -c '.users' > "${STATE_DIR}/role-${org}-${role}"
    echo '{"status":"ok"}' ;;
  /api/get-application)
    cid="${idval#*/}"; f="${STATE_DIR}/app-${cid}"
    if [ -f "${f}" ]; then jq -nc --arg n "${cid}" --arg s "$(cat "${f}")" '{data:{name:$n,clientSecret:$s}}'
    else echo '{"data":null}'; fi ;;
  /api/add-application|/api/update-application)
    cid="$(printf '%s' "${body}" | jq -r '.clientId')"
    secret="$(printf '%s' "${body}" | jq -r '.clientSecret')"
    printf '%s' "${secret}" > "${STATE_DIR}/app-${cid}"
    echo '{"status":"ok"}' ;;
  /api/get-user)
    org="${idval%%/*}"; user="${idval#*/}"
    if [ -f "${STATE_DIR}/user-${org}-${user}" ]; then jq -nc --arg n "${user}" '{data:{name:$n}}'
    else echo '{"data":null}'; fi ;;
  /api/add-user)
    org="$(printf '%s' "${body}" | jq -r '.owner')"
    user="$(printf '%s' "${body}" | jq -r '.name')"
    pass="$(printf '%s' "${body}" | jq -r '.password')"
    printf '%s' "${pass}" > "${STATE_DIR}/user-${org}-${user}"
    echo '{"status":"ok"}' ;;
  /api/login/oauth/access_token)
    grant="" fu="" fp="" fcid="" fsec=""
    for kv in "${formfields[@]:-}"; do
      case "${kv}" in
        grant_type=*) grant="${kv#grant_type=}" ;;
        username=*)   fu="${kv#username=}" ;;
        password=*)   fp="${kv#password=}" ;;
        client_id=*)  fcid="${kv#client_id=}" ;;
        client_secret=*) fsec="${kv#client_secret=}" ;;
      esac
    done
    case "${fu}" in */*) org="${fu%%/*}"; user="${fu#*/}" ;; *) org=""; user="${fu}" ;; esac
    userfile=""
    if [ -n "${org}" ] && [ -f "${STATE_DIR}/user-${org}-${user}" ]; then
      userfile="${STATE_DIR}/user-${org}-${user}"
    else
      match="$(ls "${STATE_DIR}"/user-*-"${user}" 2>/dev/null | head -1 || true)"
      [ -n "${match}" ] && userfile="${match}"
    fi
    appfile="${STATE_DIR}/app-${fcid}"
    if [ "${grant}" = "password" ] && [ -n "${userfile}" ] && [ -f "${userfile}" ] \
        && [ "$(cat "${userfile}")" = "${fp}" ] \
        && [ -f "${appfile}" ] && [ "$(cat "${appfile}")" = "${fsec}" ]; then
      jq -nc --arg t "fake-jwt-${user}" '{access_token:$t}'
    else
      echo '{"error":"invalid_grant"}'
    fi ;;
  *) echo "SHIM curl: unhandled route ${route}" >&2; echo '{}' ;;
esac
exit 0
CURL_SHIM
chmod +x "${shim_bin}/curl"

# shellcheck source=lib/e2e-password-resolve.sh
. "${here}/lib/e2e-password-resolve.sh"
# shellcheck source=lib/casdoor-seed.sh
. "${here}/lib/casdoor-seed.sh"

# ══ resolve_e2e_password ═════════════════════════════════════════════════
unset E2E_PASSWORD E2E_CREDENTIALS_FILE 2>/dev/null || true
REPO_ROOT="${work_dir}/empty-repo"
mkdir -p "${REPO_ROOT}"

set +e
out="$(resolve_e2e_password 2>"${work_dir}/stderr_unused")"; rc=$?
set -e
[ "${rc}" -ne 0 ] || fail "resolve_e2e_password: expected failure with nothing resolvable, got success \"${out}\""
[ -z "${out}" ] || fail "resolve_e2e_password: must print NOTHING to stdout on failure, got \"${out}\""
check "resolve_e2e_password fails (rc!=0, empty stdout) when nothing resolves"

export E2E_PASSWORD="shared-pw-1"
out="$(resolve_e2e_password)"
[ "${out}" = "shared-pw-1" ] || fail "resolve_e2e_password: \$E2E_PASSWORD not honoured, got \"${out}\""
check "resolve_e2e_password resolves \$E2E_PASSWORD"

export E2E_ACTIONS_ADMIN_PASSWORD="peruser-pw"
out="$(resolve_e2e_password actions-admin)"
[ "${out}" = "peruser-pw" ] || fail "resolve_e2e_password: per-user override did not win over \$E2E_PASSWORD, got \"${out}\""
out="$(resolve_e2e_password actions-user)"
[ "${out}" = "shared-pw-1" ] || fail "resolve_e2e_password: an unrelated user must still fall through to \$E2E_PASSWORD, got \"${out}\""
unset E2E_ACTIONS_ADMIN_PASSWORD
check "resolve_e2e_password: per-user \$E2E_<ROLE>_PASSWORD wins over the shared \$E2E_PASSWORD, only for that user"

unset E2E_PASSWORD
creds_file="${REPO_ROOT}/.e2e-credentials"
printf 'E2E_PASSWORD=from-file-pw\nE2E_ACTIONS_MOD_PASSWORD=from-file-mod-pw\n# a comment\n' > "${creds_file}"
out="$(resolve_e2e_password)"
[ "${out}" = "from-file-pw" ] || fail "resolve_e2e_password: shared credentials-file value not honoured, got \"${out}\""
out="$(resolve_e2e_password actions-mod)"
[ "${out}" = "from-file-mod-pw" ] || fail "resolve_e2e_password: per-user credentials-file value not honoured, got \"${out}\""
check "resolve_e2e_password falls back to the .e2e-credentials file (shared and per-user keys), never invents one"
rm -f "${creds_file}"

# ══ generate_client_secret — the exact truncation-order bug from
# project_hugmesh_e2e_password_contract.md, trap #1 ═══════════════════════
sec32="$(generate_client_secret 32)"
[ "${#sec32}" -eq 32 ] || fail "generate_client_secret 32: expected length 32, got ${#sec32} (\"${sec32}\") — this is EXACTLY the filter-after-truncate bug: head -c N BEFORE tr -dc returns ~9-11 chars for N=32, not 32"
case "${sec32}" in *[!A-Za-z0-9]*) fail "generate_client_secret: non-alnum char in \"${sec32}\"" ;; esac
sec8="$(generate_client_secret 8)"
[ "${#sec8}" -eq 8 ] || fail "generate_client_secret 8: expected length 8, got ${#sec8}"
check "generate_client_secret returns EXACTLY N alnum chars for N=8 and N=32 (mutation-tests the truncate-order bug)"

# ══ provision_and_mint_tokens — full success path ══════════════════════════
state_dir="${work_dir}/state-ok"
mkdir -p "${state_dir}"
tokens_out="${work_dir}/tokens-ok.json"
(
  export SHIM_STATE_DIR="${state_dir}"
  export PATH="${shim_bin}:${PATH}"
  export TMPDIR="${work_dir}"
  export E2E_PASSWORD="offline-pw"
  export CASDOOR_PLATFORM_CLIENT_SECRET="offline-secret"
  export CASDOOR_PLATFORM_SEED_URL="http://localhost:19999"  # never dialled — the shim intercepts curl itself
  provision_and_mint_tokens "${tokens_out}"
)
[ -f "${tokens_out}" ] || fail "provision_and_mint_tokens: success path did not write ${tokens_out}"
for k in USER PRODUCER MOD GAME-MASTER ADMIN; do
  v="$(jq -r --arg k "${k}" '.[$k] // empty' "${tokens_out}")"
  [ -n "${v}" ] || fail "provision_and_mint_tokens: missing/empty token for key ${k}"
done
check "provision_and_mint_tokens: full success writes all five non-empty tokens"

gm="$(jq -r '.["GAME-MASTER"]' "${tokens_out}")"
admin="$(jq -r '.ADMIN' "${tokens_out}")"
[ "${gm}" != "${admin}" ] || fail "REGRESSION: GAME-MASTER and ADMIN resolved to the SAME token (\"${gm}\") — actions-admin's mint overwrote actions-game-master's in the tokens map, which lets a game-master-tier scenario silently run with admin-level access"
[ "${gm}" = "fake-jwt-actions-game-master" ] || fail "GAME-MASTER token must come from actions-game-master's own mint, got \"${gm}\""
[ "${admin}" = "fake-jwt-actions-admin" ] || fail "ADMIN token must come from actions-admin's own mint, got \"${admin}\""
check "provision_and_mint_tokens: GAME-MASTER and ADMIN are minted from DIFFERENT personas, never one overwriting the other"

# Re-run against the SAME (already-provisioned) state — proves the
# create-before-mint steps are idempotent, not just the first pass.
tokens_out2="${work_dir}/tokens-ok-2.json"
(
  export SHIM_STATE_DIR="${state_dir}"
  export PATH="${shim_bin}:${PATH}"
  export TMPDIR="${work_dir}"
  export E2E_PASSWORD="offline-pw"
  export CASDOOR_PLATFORM_CLIENT_SECRET="offline-secret-2"  # a fresh per-run secret, like a real re-run
  export CASDOOR_PLATFORM_SEED_URL="http://localhost:19999"
  provision_and_mint_tokens "${tokens_out2}"
)
diff <(jq -S . "${tokens_out}") <(jq -S . "${tokens_out2}") >/dev/null \
  || fail "provision_and_mint_tokens: re-run against already-seeded state produced a DIFFERENT token map (should be the same five usernames re-minting)"
check "provision_and_mint_tokens: idempotent re-run against already-provisioned state succeeds and re-mints the same identities"

# ══ provision_and_mint_tokens — fails closed, writes NOTHING ═══════════════
state_dir_bad="${work_dir}/state-bad"
mkdir -p "${state_dir_bad}"
tokens_out_bad="${work_dir}/tokens-bad.json"
[ ! -e "${tokens_out_bad}" ] || fail "test setup error"
set +e
(
  export SHIM_STATE_DIR="${state_dir_bad}"
  export PATH="${shim_bin}:${PATH}"
  export TMPDIR="${work_dir}"
  export E2E_PASSWORD="offline-pw"
  export CASDOOR_PLATFORM_CLIENT_SECRET="offline-secret"
  export CASDOOR_PLATFORM_SEED_URL="http://localhost:19999"
  # Corrupt one role's state to a non-JSON value so _cs_add_role_member's
  # jq read fails after the role object itself was already created —
  # a genuine mid-flow failure, not a missing-prerequisite short-circuit.
  : > "${state_dir_bad}/org-hugmesh"
  echo 'NOT-JSON' > "${state_dir_bad}/role-hugmesh-USER"
  provision_and_mint_tokens "${tokens_out_bad}"
)
rc=$?
set -e
[ "${rc}" -ne 0 ] || fail "provision_and_mint_tokens: expected failure on corrupted role state, got success"
[ ! -e "${tokens_out_bad}" ] || fail "provision_and_mint_tokens: must NOT write ${tokens_out_bad} on failure (wrote it anyway)"
check "provision_and_mint_tokens: a mid-flow failure returns non-zero and writes NOTHING to the tokens path"

set +e
(
  export SHIM_STATE_DIR="${work_dir}/state-nosecret"
  export PATH="${shim_bin}:${PATH}"
  export TMPDIR="${work_dir}"
  export E2E_PASSWORD="offline-pw"
  unset CASDOOR_PLATFORM_CLIENT_SECRET
  provision_and_mint_tokens "${work_dir}/tokens-nosecret.json" 2>"${work_dir}/nosecret.err"
)
rc=$?
set -e
[ "${rc}" -ne 0 ] || fail "provision_and_mint_tokens: expected failure with an empty CASDOOR_PLATFORM_CLIENT_SECRET"
[ ! -e "${work_dir}/tokens-nosecret.json" ] || fail "provision_and_mint_tokens: must not write output when the secret precondition fails"
grep -q "CASDOOR_PLATFORM_CLIENT_SECRET is empty" "${work_dir}/nosecret.err" \
  || fail "provision_and_mint_tokens: empty-secret failure did not name the cause"
check "provision_and_mint_tokens: fails fast (no curl calls) when CASDOOR_PLATFORM_CLIENT_SECRET is unresolved"

echo "All ${pass_count} checks passed."
