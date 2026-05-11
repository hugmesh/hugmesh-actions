# snapdragon-actions

Spring service that runs platform-wide scheduled reconciliation jobs.
Stateless by design — every tick reads source-of-truth from the
canonical Casdoor instances and reconciles downstream side-effects.

## Actions

### `GameMasterAdminSyncAction`

Propagates the `game-master` role from snapdragon.systems Casdoor
into the `admin` role on snapdragon.games Casdoor. Runs every 60 s
by default (`app.actions.game-master-sync.interval-ms`).

### `GitHubOrgAdminGrantAction`

Auto-grants snapdragon.systems admin to GitHub-authenticated users
who have admin authority on any repo in (or org-level admin
membership of) a configured Snapdragon GitHub organisation
(default: `Snapdragon-Systems`, `Snapdragon`). Two entry points:

| Caller | Endpoint | Behaviour |
|---|---|---|
| Sign-up flow | `POST /api/registration/github-org-admin/evaluate` | Returns the granted Casdoor member id on success; 403 with boilerplate when the user has no Snapdragon admin reach. |
| Reconciliation (scheduled, follow-up) | n/a | Revokes admin from users whose GitHub admin reach has been removed. |

Metrics: `actions.gh_org_admin.{granted, revoked, denied, errors}`.
Resilience4j circuit breaker on the `github` instance — a GitHub
outage opens the breaker so a sign-up evaluation fails fast instead
of piling up retries.

Behaviour:

| systems.game-master | games.admin (before) | games.admin (after) |
|---|---|---|
| `{alice, bob}` | `{alice}` | `{alice, bob}` |
| `{alice}` | `{alice, dropped}` | `{alice}` |
| `{alice}` | `{alice}` | `{alice}` (no-op) |

Members are rewritten between organizations on the way through —
`snapdragon-systems/alice` → `threewizards/alice`.

## Running locally

```bash
docker compose up -d                       # two Casdoors + the action
docker compose logs -f snapdragon-actions  # watch ticks
curl localhost:8090/actuator/prometheus | grep gm_sync
```

## Metrics

| Name | Type | Meaning |
|---|---|---|
| `actions.gm_sync.added`     | counter | Members added to games-admin |
| `actions.gm_sync.removed`   | counter | Members removed from games-admin |
| `actions.gm_sync.unchanged` | counter | Ticks where membership was already in sync |
| `actions.gm_sync.errors`    | counter | Ticks that failed (read or write) |

## Configuration

Every Casdoor endpoint + credential is env-var driven (see
`application.yml` for the full list). Production deploys point at the
real snapdragon.systems + snapdragon.games Casdoor URLs; dev uses the
two containers brought up by the compose file in this repo.

## Adding new actions

Each action is a `@Service` with a `@Scheduled` method. The
`GameMasterAdminSyncAction` is the reference shape — wrap any
external side-effect in a Resilience4j `@CircuitBreaker(name=...)` so
a slow downstream doesn't pile up tick work.
