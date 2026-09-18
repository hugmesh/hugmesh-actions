# hugmesh-actions e2e suite

End-to-end tests using Playwright and playwright-bdd. The suite has
two concerns:

1. **Cross-stack admin sync** — verifies that granting or revoking the
   `game-master` role in the *systems* Casdoor instance propagates
   correctly to the *games* Casdoor instance via `hugmesh-actions`,
   and that Prometheus counters reflect each outcome.
2. **Per-role access matrix** — locks in the `SecurityConfig`
   path-matchers by hitting protected endpoints as each role tier and
   asserting the correct HTTP response.

## Layout

```
e2e/
├── features/
│   ├── sync/cross-stack-admin-sync.feature   ← the headline test
│   ├── auth/per-role-access.feature          ← role gate matrix
│   ├── steps/
│   │   ├── sync-steps.ts
│   │   ├── auth-steps.ts
│   │   └── helpers/{casdoor.ts, actions.ts, login-ui.ts}
│   └── support/fixtures.ts                   ← AfterStep PNG hook
├── playwright.config.ts                      ← bdd project, video=on
├── scripts/bring-up-stack.sh                 ← docker compose orchestrator
├── package.json
└── results/                                  ← HTML report + videos
```

## Running

Run all commands from the repository root.

```bash
# 1. Start casdoor-systems, casdoor-games, and hugmesh-actions via Docker
#    Compose, then seed the systems Casdoor and mint real per-role bearer
#    tokens (see "Token wiring" below). Needs $E2E_PASSWORD or a
#    .e2e-credentials file resolvable BEFORE this runs — see "Password
#    wiring". FAILS (exit 1) if the password can't be resolved, or if
#    seeding/minting doesn't fully complete:
bash e2e/scripts/bring-up-stack.sh

#    To skip the seeder entirely and run only the anonymous scenarios
#    (no Casdoor provisioning attempted):
E2E_ALLOW_ANONYMOUS_ONLY=1 bash e2e/scripts/bring-up-stack.sh

# 2. Install dependencies and run the full BDD suite.
npm --prefix e2e install
npm --prefix e2e test

# 3. Open the HTML report in your browser (per-step PNGs + per-scenario videos).
npm --prefix e2e run report

# 4. Tear down all containers when done.
bash e2e/scripts/bring-up-stack.sh stop
```

## What the suite proves

### `cross-stack-admin-sync.feature` (3 scenarios)

Each scenario drives the full sync loop: it seeds state in both
Casdoor instances, triggers a sync tick in `hugmesh-actions`, then
asserts both the Casdoor side-effect and the Prometheus counter.

```mermaid
sequenceDiagram
    participant T as Test (step)
    participant S as casdoor-systems
    participant A as hugmesh-actions
    participant G as casdoor-games
    participant P as /actuator/metrics

    T->>S: seed role state (grant or revoke game-master)
    T->>T: wait out one scheduled tick\n(GM_SYNC_INTERVAL_MS, shortened to 2s in e2e)
    A->>S: read current game-masters
    A->>G: read current admins
    A->>G: apply diff (add / remove / skip)
    A->>P: increment gm_sync.added / .removed / .unchanged
    T->>G: assert admin membership changed (or unchanged)
    T->>P: assert counter incremented
```

| Scenario | Arrange | Trigger | Assert |
|---|---|---|---|
| grant flows through | User exists on both Casdoors; no admin role on games | Grant `game-master` on systems | Next sync tick adds admin on games; `gm_sync.added` counter increments |
| revoke flows through | User holds `game-master` on systems and admin on games | Remove `game-master` on systems | Next tick removes admin on games; `gm_sync.removed` counter increments |
| in-sync is no-op | Both sides have empty role sets | Sync tick | No Casdoor write occurs; `gm_sync.unchanged` counter increments |

### `per-role-access.feature` (6 scenarios)

One scenario per role tier hits three endpoints that each require a
different authority level, asserting the correct HTTP status for that
tier. A regression in any `SecurityConfig` path-matcher causes the
corresponding scenario to fail immediately.

| Endpoint | Required role | Anonymous | player | producer | mod | game-master | admin |
|---|---|---|---|---|---|---|---|
| `GET /actuator/health` | public | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /actuator/metrics` | `ROLE_GAME-MASTER` | 401 | 403 | 403 | 403 | 200 | 200 |
| `POST /api/registration/github-org-admin/evaluate` | `ROLE_ADMIN` | 401 | 403 | 403 | 403 | 403 | 200 |

> **Note:** The `200` for `admin` above is the target behaviour once the
> GitHub-org-admin feature is enabled. The feature's master switch
> (`app.actions.gh-org-admin.enabled` / `GH_ORG_ADMIN_ENABLED`) defaults to
> `false` and is not set in `compose.yaml` or `bring-up-stack.sh`, so as the
> stack is wired today an admin caller gets `404` instead. See the top-level
> README's Known Gaps section.

> **What is actually asserted today.** `bring-up-stack.sh` now mints a
> real per-role bearer token for all five authenticated tiers (see
> [Token wiring](#token-wiring)) — but that mint has been proven
> **offline only**: a shimmed `curl` standing in for Casdoor's admin API
> + OAuth2 password grant (`e2e/scripts/test-casdoor-seed.sh`), never a
> real `casbin/casdoor` container. **A person still needs to run
> `bash e2e/scripts/bring-up-stack.sh` followed by `npm --prefix e2e
> test` against a real compose stack to confirm the six
> `per-role-access.feature` scenarios pass end-to-end** — see the root
> README / hugmesh-actions#7 for what to look for. The SecurityConfig
> path-matchers themselves are meanwhile locked in by the JVM
> `SecurityConfigPathRulesTest` / `SecurityConfigSpecPathsTest` unit
> tests, which do not need tokens.

## Reports

Every run writes the following artifacts under `e2e/results/`:

| Artifact | Path | Notes |
|---|---|---|
| HTML report | `html/index.html` | Browsable; open with `npm --prefix e2e run report` |
| Step screenshots | `artifacts/<test>/attachments/step-NN-….png` | One PNG per Gherkin step, linked inside the HTML report |
| Scenario video | `artifacts/<test>/video.webm` | Captured for every scenario, not just failures |
| Trace archive | `artifacts/<test>/trace.zip` | Failure only; replay with `npx playwright show-trace <path>` |

## Login policy — native login-ui form only, never Casdoor's hosted page

Two distinct things get conflated as "login". This suite keeps them apart:

**1. Browser login → OUR login-ui native form (never Casdoor).**
Any scenario that logs a user in through a real browser goes through our own
login-ui native username/password form — never Casdoor's hosted login page.
The helper lives in `helpers/login-ui.ts`:

- `loginViaNativeForm(page, { username, password, mfaPasscode? })` fills the
  login-ui form by its test ids — `login-username`, `login-password`,
  `login-submit` — and submits.
- Submitting POSTs `/bff/login` (user-bff, reached **via be-gateway**;
  `permitAll` at the BFF security layer). user-bff runs the entire OIDC/PKCE
  handshake with Casdoor **server-side**: it generates the PKCE
  `code_verifier` + S256 `code_challenge` and a random `state` itself, POSTs
  Casdoor's internal `/api/login`, exchanges the returned code via auth-core's
  `OidcExchangeClient`, and completes provision + custody + cookie.
- The browser never receives a JWT / access / refresh / id token, the
  `code_verifier`, the `state`, or any Casdoor URL. The only credential handed
  back is the opaque, httpOnly `bff_<opaque>` session cookie in the
  `Set-Cookie` header (`SameSite=Lax`, host-only). **The session token appears
  only in Set-Cookie, never in the response body.**

`/bff/login` response contract:

| Case | Status | Body | Cookie |
|---|---|---|---|
| success | 200 | `{ registrationComplete, status:"active", mfaRequired:false }` | `Set-Cookie: bff_<opaque>` |
| MFA required | 200 | `{ mfaRequired:true, mfaType:[…] }` | none (no session minted) |
| bad credentials | 401 | `{ error:"invalid_credentials", message:<casdoor msg> }` | none |
| SSO surface dark | 503 | — | none |

(503 when `app.bff.oidc.enabled=false` or the custody key is blank.)

`bffLogin(creds)` in the same helper is a headless stand-in that POSTs
`/bff/login` through be-gateway directly — still OUR contract endpoint, never
Casdoor's hosted form — for asserting the response contract without a browser.

> **login-ui dependency.** The browser helper drives login-ui test ids
> `login-username` / `login-password` / `login-submit`, plus
> `login-mfa-passcode` for the MFA follow-up (login-ui will add this one). The
> browser-login helpers target the full platform stack via `LOGIN_UI_URL`
> (default `http://localhost:8081`) and `GATEWAY_BASE_URL` (default
> `http://localhost:8080`); the actions-only compose in this repo does not ship
> login-ui / be-gateway / user-bff, so browser-login scenarios require the full
> platform stack to be up.

**2. Server-side token acquisition for test SETUP → may hit Casdoor directly.**
Minting a Bearer JWT to seed or assert cross-stack state directly against the
actions API is **server-to-server**, not a browser login, so it is allowed to
call Casdoor's token endpoint directly. Those paths are labelled
**SERVER-SIDE ONLY**:

- `helpers/casdoor.ts` — Casdoor admin REST API (add-user / get-role /
  update-role) that seeds and asserts role state.
- `scripts/lib/casdoor-seed.sh` (invoked by `scripts/bring-up-stack.sh`) —
  provisions the systems Casdoor (org, five role objects, the
  hugmesh-actions OAuth app, one user per role tier) and mints per-role
  Bearer JWTs via Casdoor's OAuth2 password grant / token endpoint. See
  "Token wiring" below.

Neither renders Casdoor's hosted login form or logs a user in through a browser.

## Token wiring

`auth-steps.ts` supplies bearer tokens to role-authenticated requests.
It looks for tokens in the following order:

1. **`e2e/.tokens.json`** — written by `bring-up-stack.sh` (via
   `scripts/lib/casdoor-seed.sh`'s `provision_and_mint_tokens`) once the
   systems Casdoor is seeded — see the Note below for what that seeding
   does and its one unverified step. `auth-steps.ts`'s `tokenFor()` looks
   the key up by the *exact* role string the `.feature` file passes to
   `Given "<actor>" is authenticated with role "<ROLE>"` (no case/format
   translation), so the keys must match those literally:
   ```json
   {
     "ADMIN":       "<bearer token>",
     "GAME-MASTER": "<bearer token>",
     "PRODUCER":    "<bearer token>",
     "MOD":         "<bearer token>",
     "USER":        "<bearer token>"
   }
   ```
2. **Environment variables** — fallback when `.tokens.json` is absent
   or missing a key (`tokenFor()` upper-cases and `-`→`_`s the role for
   this lookup only): `TOKEN_ADMIN`, `TOKEN_GAME_MASTER`, `TOKEN_PRODUCER`,
   `TOKEN_MOD`, `TOKEN_USER`.

> **Note (Decision 62, operator, 2026-09-18, hugmesh-actions#7): this
> repo now runs its OWN Casdoor seeder** —
> `e2e/scripts/lib/casdoor-seed.sh`'s `provision_and_mint_tokens`, called
> from `bring-up-stack.sh` via the `mint_tokens_or_fail` guard
> (`e2e/scripts/lib/token-mint-guard.sh`) — rather than consuming
> hugmesh-generator's `tests/stack/seed-e2e.sh` (which targets a
> different topology: a single Casdoor on `:8000` behind a `:8088` front
> door, not this stack's two standalone Casdoors on `:8001`/`:8002`).
>
> The three prerequisites measured 2026-09-09 are now fixed:
>
> 1. **Both Casdoors are wired to their own Postgres** — `driverName:
>    postgres` / `dataSourceName: ...host=pg-systems...` (and
>    `pg-games`) in `compose.yaml`, replacing the image's MySQL default.
> 2. **The seeder provisions the identities the grant needs** — org
>    `hugmesh`, five bare role objects (`USER`, `PRODUCER`, `MOD`,
>    `GAME-MASTER`, `ADMIN` — the JWT→authority converter,
>    `CasdoorAuthorities` in hugmesh-commons, upper-cases and
>    `ROLE_`-prefixes whatever the `roles` claim carries, so the Casdoor
>    role NAME must be bare, never pre-prefixed), the `hugmesh-actions`
>    OAuth app (`grantTypes` includes `password`; `tokenAttributes` maps
>    Casdoor's `Roles` field into the JWT `roles` claim), and one test
>    user per role tier (`actions-user`, `actions-producer`,
>    `actions-mod`, `actions-game-master`, `actions-admin` — the last
>    holds BOTH `ADMIN` and `GAME-MASTER` in Casdoor, because
>    `SecurityConfig` gates `/actuator/**` on `hasRole("GAME-MASTER")`
>    specifically and the admin scenario asserts `200` there; its token
>    is filed only under the `ADMIN` key, never under `GAME-MASTER` too —
>    that key's token comes from `actions-game-master`'s own mint).
>    `CASDOOR_PLATFORM_CLIENT_SECRET` / `CASDOOR_GAMES_CLIENT_SECRET` are
>    now generated per run by `bring-up-stack.sh` (`generate_client_secret`,
>    `lib/e2e-password-resolve.sh`) and exported **before** `docker compose
>    up`, so the app the seeder creates and the secret the running
>    `hugmesh-actions` container was booted with match.
> 3. **`origin` is pinned on both Casdoors** to the URL `hugmesh-actions`
>    validates (`http://casdoor-systems:8000` /
>    `http://casdoor-games:8000` — its own `CASDOOR_PLATFORM_URL` /
>    `CASDOOR_GAMES_URL`), so every minted token's `iss` matches
>    regardless of which host actually dialled Casdoor to mint it.
>
> **Scope.** This seeds ONLY the per-role-access-matrix tokens above,
> systems Casdoor only. `cross-stack-admin-sync.feature`'s GAMES-side
> identities (the lowercase `game-master`/`admin` role objects that
> feature grants directly, on **both** Casdoors, via `helpers/casdoor.ts`)
> are **not** provisioned by this seeder — that remains a separate,
> open gap (tracked as a follow-up issue off hugmesh-actions#7).
>
> **Fail-closed, unchanged in spirit.** On ANY step failing —
> unresolved password, unreachable Casdoor, a create/verify call that
> doesn't come back right, a mint with no `access_token` — the whole
> pass returns non-zero, `e2e/.tokens.json` is overwritten with an empty
> `{}` (never a partial 3-of-5 map), and `bring-up-stack.sh` exits 1.
> `E2E_ALLOW_ANONYMOUS_ONLY=1` skips the seeder outright and always
> succeeds with a `⚠` warning banner, never a plain `✓`.
>
> **What is verified, and what is not.** `resolve_e2e_password`,
> `generate_client_secret`, and the full `provision_and_mint_tokens`
> control flow (create-before-mint ordering, idempotent re-run, and the
> per-persona token-map regression above) are unit-tested **offline**
> against a shimmed `curl` standing in for Casdoor
> (`e2e/scripts/test-casdoor-seed.sh`) — no live Docker, no real
> Casdoor. **The mint has never been run against a real
> `casbin/casdoor` container in this session.** A person still needs to
> run `bash e2e/scripts/bring-up-stack.sh` against the real
> `compose.yaml` and confirm: it exits 0, `e2e/.tokens.json` holds five
> non-empty, non-identical tokens, and `npm --prefix e2e test` then
> passes all six `per-role-access.feature` scenarios (not just the
> anonymous one).

## Password wiring

`helpers/casdoor.ts`'s `ensureUser()` seeds each test Casdoor account with a
password resolved by `helpers/e2e-credentials.ts`'s `resolveE2ePassword()`.
This repo is a **consumer** of the org-wide e2e credential contract — it
never generates or hardcodes a password. Precedence:

1. **`$E2E_<USERNAME>_PASSWORD`** — per-user override, e.g.
   `E2E_ALICE_PASSWORD` for the `alice` fixture in
   `cross-stack-admin-sync.feature`.
2. **`$E2E_PASSWORD`** — shared override, or same-run propagation from
   whatever process generated it first.
3. **The run-state credentials file** — `$E2E_CREDENTIALS_FILE` if set,
   otherwise the nearest `.e2e-credentials` walking up from `process.cwd()`
   (so it finds the one at repo root when tests run from `e2e/`). Same two
   keys, `KEY=value` lines. This repo only reads this file — it is written
   by `make stack-seed` (hugmesh-generator's `e2e_password_ensure()`) — and
   never fails just because the file doesn't exist yet; it falls through to
   the next step.
4. **Throw.** If nothing above resolves, `resolveE2ePassword()` throws
   naming `make stack-seed` and the exact path it looked for. There is no
   literal fallback — see the org e2e-password contract's Rule 4.

`e2e/scripts/lib/e2e-password-resolve.sh`'s `resolve_e2e_password` is the
bash-side port of the same precedence, used by `casdoor-seed.sh` to seed the
five auth-matrix test users' passwords — same rule, same consumer-only
posture, resolved by `bring-up-stack.sh` **before** `docker compose up`. The
Casdoor OAuth app's own **client secret** (`CASDOOR_PLATFORM_CLIENT_SECRET` /
`CASDOOR_GAMES_CLIENT_SECRET`) is a *different*, machine-only credential that
same file's `generate_client_secret` DOES generate — it has no cross-repo
consumer to stay in sync with, unlike the shared human test password.

## Known Gaps / TODO

- **`GM_SYNC_INTERVAL_MS` is never actually shortened for e2e runs.**
  `bring-up-stack.sh` used to assert this override twice — in its header
  and in an "an e2e overlay that shortens the sync tick" body comment. Both
  assertions have been removed from the script (it now states the opposite),
  but **the gap itself is unfixed**: no overlay file exists, the script runs
  `docker compose -f compose.yaml up -d --wait` against the repo-root
  `compose.yaml`, and that file sets no `GM_SYNC_INTERVAL_MS` on the
  `hugmesh-actions` service — so the action service runs the
  `application.yml` default of `60000`ms
  (`src/main/resources/application.yml:47`,
  `${GM_SYNC_INTERVAL_MS:60000}`). Meanwhile
  `waitForSyncTick()` (`e2e/features/steps/helpers/actions.ts:38-43`)
  falls back to `process.env.GM_SYNC_INTERVAL_MS ?? '2000'` and only
  waits `2.2×` that value (~4.4 s) when the env var isn't set. As wired
  today the sync scenarios wait roughly 4.4 s for a tick that actually
  fires every 60 s, which reads as a likely source of flakes/failures
  in `cross-stack-admin-sync.feature` rather than the documented
  guaranteed tick crossing. Either add the missing compose override (set
  `GM_SYNC_INTERVAL_MS=2000` on the `hugmesh-actions` service for the
  e2e stack) or raise the helper's default/timeout to match the real
  60 s interval.

- **`cross-stack-admin-sync.feature`'s Casdoor identities are still
  unseeded.** `casdoor-seed.sh` (Decision 62) provisions only the
  per-role-access-matrix's org/roles/app/users on the SYSTEMS Casdoor.
  The sync feature's own `game-master` (systems) / `admin` (games) role
  objects, and the games-side org/app, are not created by anything in
  `bring-up-stack.sh` — `helpers/casdoor.ts`'s `ensureUser` /
  `addRoleMember` calls assume they already exist. Tracked as a
  follow-up off hugmesh-actions#7.
