# snapdragon-actions e2e suite

Playwright + playwright-bdd. Verifies the cross-stack admin sync
end-to-end against two real Casdoor instances + the
`snapdragon-actions` service, plus a per-role access matrix that
locks in the SecurityConfig path-matchers.

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

```bash
# Bring up casdoor-systems + casdoor-games + snapdragon-actions.
bash e2e/scripts/bring-up-stack.sh

# Install + run the BDD project.
npm --prefix e2e install
npm --prefix e2e test

# Open the HTML report (per-step PNGs + per-scenario videos).
npm --prefix e2e run report

# Stop everything when done.
bash e2e/scripts/bring-up-stack.sh stop
```

## What the suite proves

### `cross-stack-admin-sync.feature` (3 scenarios)

| Scenario | Arrange | Trigger | Assert |
|---|---|---|---|
| grant flows through | user exists on both Casdoors, no admin on games | grant game-master on systems | next sync tick lands admin on games; `gm_sync.added` counter ++ |
| revoke flows through | user has game-master on systems AND admin on games | remove game-master on systems | next tick strips admin on games; `gm_sync.removed` counter ++ |
| in-sync is no-op | empty sets on both sides | sync tick | no Casdoor write; `gm_sync.unchanged` counter ++ |

### `per-role-access.feature` (6 scenarios)

One scenario per role tier (anonymous / player / producer / mod /
game-master / admin) hitting:

- `/actuator/health` → public probe
- `/actuator/metrics` → `ROLE_GAME-MASTER`
- `/api/registration/github-org-admin/evaluate` → `ROLE_ADMIN`

If a SecurityConfig path-matcher regresses, the matching role
scenario fails loudly.

> **What is actually asserted today.** Only the **anonymous** scenario
> runs end-to-end right now — it needs no bearer token. The five
> authenticated tiers (player / producer / mod / game-master / admin)
> depend on real per-role tokens, and the bring-up's token-generation
> step is still a stub (see [Token wiring](#token-wiring)); until it is
> wired, those scenarios fail at the `Given "<role>" is authenticated`
> step (the helper throws when no token is present) rather than
> exercising the role gate. The SecurityConfig path-matchers themselves
> are meanwhile locked in by the JVM `SecurityConfigPathRulesTest` /
> `SecurityConfigSpecPathsTest` unit tests, which do not need tokens.

## Reports

Every run produces:

- `results/html/index.html` — Playwright's HTML report, browsable.
- `results/artifacts/<test>/attachments/step-NN-...png` — one PNG
  per Gherkin step, attached to the report.
- `results/artifacts/<test>/video.webm` — full per-scenario video
  (always; not just on failure).
- `results/artifacts/<test>/trace.zip` — on failure only; replay
  with `npx playwright show-trace …`.

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
- `scripts/bring-up-stack.sh` — per-role Bearer JWT minting via Casdoor's
  OAuth2 password grant / token endpoint.

Neither renders Casdoor's hosted login form or logs a user in through a browser.

## Token wiring

`auth-steps.ts` reads per-role bearer tokens from
`e2e/.tokens.json` (written by the bring-up script after the
Casdoor seeds settle). If the file is missing the helper falls
back to env vars: `TOKEN_ADMIN`, `TOKEN_GAME_MASTER`,
`TOKEN_PRODUCER`, `TOKEN_MOD`, `TOKEN_USER`. The bring-up's token-
generation step is currently a stub — wiring it to the real
Casdoor token-grant endpoint is a follow-up; tests for now
exercise the unauthenticated lanes (anonymous + sync action +
counter assertions) without needing real tokens.
