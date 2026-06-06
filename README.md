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
| Sign-up flow | `POST /api/registration/github-org-admin/evaluate` | Returns the granted Casdoor member id on success; 403 with boilerplate when the user has no Snapdragon admin reach. The forwarded GitHub token is cached so the reconciler can re-check the user later. |
| Reconciliation (scheduled) | `GitHubOrgAdminReconcileAction`, every `app.actions.gh-org-admin.reconcile.interval-ms` (default 6 h) | Revokes admin from users whose GitHub admin reach has been removed. |

Metrics: `actions.gh_org_admin.{granted, revoked, denied, errors,
reconciled, reconcile_skipped, reconcile_errors}`.
Resilience4j circuit breaker on the `github` instance — a GitHub
outage opens the breaker so a sign-up evaluation fails fast instead
of piling up retries.

#### `GitHubOrgAdminReconcileAction` (scheduled revoke loop)

Closes audit gap #5: an admin who loses GitHub org-admin reach must
lose snapdragon.systems admin automatically, not only at next sign-in.
Mirrors `GameMasterAdminSyncAction`'s shape (reactive `Mono` pipeline,
resilience4j `@CircuitBreaker(name="github")`, configurable
`interval-ms` / `initial-delay-ms`).

Each tick:

1. Reads the current member list of the systems-admin role from
   snapdragon.systems Casdoor.
2. For every member we hold a **fresh** GitHub token for (seeded by the
   sign-up flow into the in-memory `GitHubTokenCache`, TTL
   `app.actions.gh-org-admin.token-ttl`, default 24 h), replays that
   token against GitHub and revokes systems-admin **iff** their reach
   has dropped to zero.
3. Members with no fresh token are **skipped** — never revoked on
   missing/stale evidence. They are re-evaluated on their next sign-in.

The loop is **prune-only** (it only ever removes admin, never grants)
and **idempotent** (a tick where everyone still has reach is a pure
read with zero writes). Per-user GitHub failures are swallowed so one
bad token doesn't abort the rest of the tick. The token cache is
process-local + best-effort: losing it (pod restart) only delays an
auto-revoke until the user signs in again; the grants themselves live
in Casdoor.

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
| `actions.gh_org_admin.granted`            | counter | Users granted systems-admin via GitHub org membership |
| `actions.gh_org_admin.revoked`            | counter | Users stripped of systems-admin after losing GitHub reach |
| `actions.gh_org_admin.denied`             | counter | Sign-up requests rejected (no Snapdragon org admin) |
| `actions.gh_org_admin.errors`             | counter | Sign-up evaluations that errored |
| `actions.gh_org_admin.reconciled`         | counter | Admins re-evaluated against GitHub on a reconcile tick |
| `actions.gh_org_admin.reconcile_skipped`  | counter | Admins left untouched for lack of a fresh GitHub token |
| `actions.gh_org_admin.reconcile_errors`   | counter | Reconcile ticks / per-user checks that failed |

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

## Native image (GraalVM)

The build carries a `native` profile (same shape as
`snapdragon-gateway`) plus app-specific AOT hints in
`NativeHintsConfig`:

```bash
./mvnw -Pnative native:compile      # requires a GraalVM native-image toolchain
```

`NativeHintsConfig` registers reflection hints for the JSON-bound DTOs
that cross the HTTP boundary (the registration controller's
request/response records and the `GitHubClient` response records) and
for the resilience4j-`@CircuitBreaker`-advised action beans
(`GameMaster…`, `GitHubOrgAdminGrant…`, `GitHubOrgAdminReconcile…`).
The Casdoor/GitHub WebClients deserialize into raw `Map`/`List`, which
are already reflection-safe; Spring Boot AOT covers the WebClient,
OAuth2 resource-server, scheduling, and Jackson framework surface, and
the parent profile's `add-reachability-metadata` goal supplies the
library-side metadata.

**Status:** the AOT pipeline is verified — `./mvnw -Pnative
spring-boot:process-aot` succeeds and the generated
`reflect-config.json` contains all hinted types. A full native binary
was **not** compiled in this environment (no GraalVM `native-image`
toolchain present); run the `native:compile` command above on a
GraalVM JDK to produce the binary.
