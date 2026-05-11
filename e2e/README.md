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
│   │   └── helpers/{casdoor.ts, actions.ts}
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

## Reports

Every run produces:

- `results/html/index.html` — Playwright's HTML report, browsable.
- `results/artifacts/<test>/attachments/step-NN-...png` — one PNG
  per Gherkin step, attached to the report.
- `results/artifacts/<test>/video.webm` — full per-scenario video
  (always; not just on failure).
- `results/artifacts/<test>/trace.zip` — on failure only; replay
  with `npx playwright show-trace …`.

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
