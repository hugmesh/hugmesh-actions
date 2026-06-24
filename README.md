# snapdragon-actions

Reactive Spring (WebFlux, Java 21, `@EnableScheduling`) service that runs
the Snapdragon platform's **cross-stack role-reconciliation** jobs and
hosts the **registration org-admin grant** endpoint. Stateless by
design — every scheduled tick reads source-of-truth from the canonical
Casdoor instances (and GitHub) and reconciles the downstream
side-effect; the only durable state lives in Casdoor, never here (the
GitHub-token cache is a process-local, best-effort optimisation —
losing it on a restart only delays an auto-revoke).

## Where it sits in the platform

| | |
|---|---|
| **Tier** | Backend effector / reconciler — talks to Casdoor admin APIs + GitHub; not a user-facing UI. |
| **In-cluster identity** | Deployed under the name **`snapdragon-api`** (its mTLS identity is the `snapdragon-api-mtls` ESO Secret). The deployment manifest lives in `snapdragon-generator` (`specs/cluster/manifests/snapdragon-apis/`), not in this repo. See [SECURITY.md](SECURITY.md). |
| **Port** | `8090` local/dev (env `PORT`); the in-cluster deployment maps it to `8080`. |
| **Fronted by** | `snapdragon-be-gateway` — the legitimate caller of `/api/registration/**`; an external client never reaches this API directly. |
| **Trusts** | The **snapdragon.systems Casdoor** as its canonical JWT issuer (this surface mutates state in *both* the systems and games Casdoors, so only the systems side is trusted to authorize the call). |

The reconcilers in this service are what make two platform invariants
self-healing rather than sign-in-only: a `game-master` on
snapdragon.systems automatically becomes an `admin` in the **optional,
opt-in games realm** (the games service is OFF by default and only
present when the build is generated with `--enable-games`), and a
platform admin who loses their GitHub org-admin reach automatically
loses snapdragon.systems admin.

```mermaid
flowchart LR
    GW["snapdragon-be-gateway<br/>(mTLS client cert)"] -->|"POST /api/registration/**<br/>ROLE_ADMIN JWT"| API

    subgraph API["snapdragon-actions (snapdragon-api)"]
        RC["RegistrationController"]
        GM["GameMasterAdminSyncAction<br/>@Scheduled 60s"]
        REV["GitHubOrgAdminReconcileAction<br/>@Scheduled 6h"]
        GHA["GitHubOrgAdminGrantAction"]
        OIDC["OidcDefaultRoleProbeAction<br/>@Scheduled (default-off)"]
        RC --> GHA
    end

    SC["snapdragon.systems Casdoor<br/>(canonical JWT issuer)"]
    GC["games Casdoor<br/>(optional service, opt-in)"]:::optional
    GH["GitHub API"]
    BFF["snapdragon-admin-bff-api<br/>(ROLE_SERVICE first-login hook)"]

    GM -->|"read game-master / write admin"| SC
    GM -->|"write games admin<br/>(when games enabled)"| GC
    GHA -.->|"grant/deny systems admin<br/>(disabled by default)"| SC
    GHA -.->|"check org-admin reach<br/>(disabled by default)"| GH
    REV -.->|"prune-only revoke<br/>(no-op when disabled)"| SC
    OIDC -.->|"read new users<br/>(disabled by default)"| SC
    OIDC -.->|"apply OIDC default roles<br/>(disabled by default)"| BFF

    MFA["MFA / acr step-up on ROLE_ADMIN"]:::planned
    MFA -.-> RC

    classDef planned stroke-dasharray:4 5,stroke:#999,color:#999
    classDef optional stroke-dasharray:3 3,stroke:#888,color:#666
```

Solid edges are **✅ shipped** and active by default; the dotted
GitHub-org-admin and OIDC-default-role edges are shipped but **gated off
by default** (`app.actions.gh-org-admin.enabled=false`,
`app.actions.oidc-default-roles.enabled=false`); the greyed `MFA` node is
**🔭 planned** for the endpoint-enforcement guard — see
[Roadmap](#roadmap--planned-not-shipped) (note: the MFA *converter*
support already ships dark on `main`, see Current State).

## Actions

### `GameMasterAdminSyncAction` — systems game-master → games admin

> Role-gated and tied to the **optional games service** (Snapdragon Game
> Studio), which is OFF by default and only enabled when a build is
> generated with `--enable-games` / `ENABLE_GAMES=true`. When games is
> not deployed this reconciler simply has no games Casdoor to write to;
> it is the cross-stack reconciler that propagates a domain
> admin/superadmin holding the `game-master` role into the games realm as
> a games admin.

Propagates the `game-master` role from snapdragon.systems Casdoor into
the `admin` role on the games Casdoor. Scheduled every
`app.actions.game-master-sync.interval-ms` (default **60 s**).

Each tick reads both role memberships, computes the desired games-admin
set by rewriting every systems member's `<org>/<username>` identifier to
the games organization (`snapdragon-systems/alice` →
`threewizards/alice`), and POSTs the new membership to games Casdoor
**only when it actually differs**. Idempotent (a no-change tick writes
nothing) and stateless. The per-Casdoor side-effect is wrapped in a
Resilience4j `@CircuitBreaker(name="casdoor")` so a stalled Casdoor
opens the breaker and the next tick retries instead of piling up work.

| systems `game-master` | games `admin` (before) | games `admin` (after) |
|---|---|---|
| `{alice, bob}` | `{alice}` | `{alice, bob}` |
| `{alice}` | `{alice, dropped}` | `{alice}` |
| `{alice}` | `{alice}` | `{alice}` (no-op) |

### `GitHubOrgAdminGrantAction` — GitHub org admin → systems admin

> **Disabled by default (✅ shipped).** The whole GitHub-org-admin
> auto-grant feature is gated behind a master switch
> `app.actions.gh-org-admin.enabled` (env `GH_ORG_ADMIN_ENABLED`),
> **default `false`**. While disabled the evaluate endpoint returns
> **`404`** (not `403`), the scheduled reconciler is a **no-op** (no
> GitHub calls, no Casdoor writes), and the cached-token path is never
> seeded. Superadmin opts in by setting the env var and rolling the pod;
> everything below describes the **enabled** behaviour.

Auto-grants snapdragon.systems `admin` to a GitHub-authenticated user
who holds admin authority on a configured Snapdragon GitHub org —
either **org-level admin** (`GET /user/memberships/orgs/{org}` → `admin`)
**or** admin permission on **any repo** owned by that org (a paginated
walk of `GET /user/repos`). Orgs default to
`Snapdragon-Systems,Snapdragon` (`app.actions.gh-org-admin.orgs`); the
granted role defaults to `admin` (`…systems-admin-role`).

It has two entry points, plus a scheduled revoke loop:

| Caller | Trigger | Behaviour |
|---|---|---|
| **Sign-up (on demand)** | `POST /api/registration/github-org-admin/evaluate` | Evaluates the forwarded GitHub token; on a match adds the user to systems-admin and returns the Casdoor member id, otherwise `403`. Caches the forwarded GitHub token so the reconciler can re-check the user later. |
| **Reconcile (scheduled)** | `GitHubOrgAdminReconcileAction` | Revokes systems-admin from users whose GitHub admin reach has dropped to zero. |

GitHub admin reach is only visible through **the user's own token**
(there is no service-account view of another user's private org/repo
admin), so the evaluate call seeds that token into an in-memory
`GitHubTokenCache` (TTL `app.actions.gh-org-admin.token-ttl`, default
**24 h**) for the reconciler to replay. The GitHub-side calls are
guarded by a `@CircuitBreaker(name="github")` so a GitHub outage fails
fast instead of cascading through every sign-up evaluation.

### `GitHubOrgAdminReconcileAction` — scheduled prune-only revoke loop

Closes the audit gap where an admin who loses GitHub org-admin reach
would otherwise keep systems-admin until their next sign-in. Mirrors the
sync action's shape (reactive `Mono`/`Flux` pipeline,
`@CircuitBreaker(name="github")`, configurable `…reconcile.interval-ms` /
`…initial-delay-ms`, default **6 h**).

Each tick:

1. Reads the current member list of the systems-admin role from
   snapdragon.systems Casdoor.
2. **Phase 1 — concurrent read-only scan** (bounded to 4 in flight):
   for every member we hold a **fresh** GitHub token for, replays it
   against GitHub and collects the usernames whose reach has dropped to
   zero. Members with no fresh token are **skipped** (never revoked on
   missing/stale evidence); a transient/inconclusive GitHub error is
   also treated as missing evidence and skipped.
3. **Phase 2 — single serialized write**: removes the whole loser set in
   one `getRoleMembers → removeAll → setRoleMembers`, which closes the
   lost-update race that a per-user read-modify-write would have. An
   empty loser set writes nothing.

The loop is **prune-only** (it only ever removes admin, never grants)
and **idempotent** (a tick where everyone still has reach is a pure read
with zero writes). The token cache is process-local and best-effort:
losing it (a pod restart) only delays an auto-revoke until the user
signs in again; the grants themselves live in Casdoor.

### `OidcDefaultRoleProbeAction` — OIDC default roles for new sign-ups

> **Disabled by default (✅ shipped).** Gated behind
> `app.actions.oidc-default-roles.enabled` (env
> `APP_OIDC_DEFAULT_ROLES_ENABLED`), **default `false`** — exactly like the
> GitHub-org-admin grant. While disabled the scheduled tick returns
> immediately (no Casdoor read, no admin-bff call), and the
> `@ConditionalOnProperty` role-hook client bean does not even exist, so
> the action short-circuits a second way. Everything below describes the
> **enabled** behaviour.

Supplies the missing **caller** for the registration-flow default-role
hook in `snapdragon-admin-bff-api`: admin-bff already reads the
per-`(domain, provider)` default-role map and applies it via Casdoor, but
Casdoor exposes no first-login webhook in this org, so this scheduled
probe observes the newest Casdoor users and calls the existing
ROLE_SERVICE endpoint `POST /bff/internal/oidc/first-login` once per new
signup. Scheduled every `app.actions.oidc-default-roles.interval-ms`
(default **5 min**), it reads the `limit` newest users, keeps only those
created within the `lookback` window whose `signupApplication` maps to a
configured provider and that it hasn't already seen, and applies each
user's provider defaults.

Idempotent and stateless like the other reconcilers: a process-local,
best-effort seen-set (mirroring `GitHubTokenCache`) prevents re-POSTing
within a pod lifetime and is pruned to the lookback window each tick, the
underlying `addUserToRole` is itself idempotent, and even fully enabled a
provider with no default-role map configured results in zero Casdoor
writes. Metrics: `actions.oidc_default_roles.{applied,skipped,errors}`.

## HTTP surface

The only request endpoint is the registration org-admin gate; the rest
of the service is scheduled work with no request principal.

`POST /api/registration/github-org-admin/evaluate` — body
`{ githubAccessToken, snapdragonUsername }` (both bounded + charset-
validated by Jakarta Bean Validation → `400` on malformed input). Called
by the **registration remote's BFF** after a new user
completes external GitHub OAuth; it forwards the user's GitHub
access-token + their freshly-minted Casdoor username. The controller, in
order:

1. **Local reserved-username gate** (runs unconditionally) — rejects
   `422` any username that, normalised to the vanity-subdomain DNS-label
   form, collides with a dedicated gateway route/prefix. The reserved
   list (`ReservedNames`, currently **70 labels**) is the canonical union
   of the fe-/be-gateway route prefixes, reserved subdomains, the
   app routes of the optional (opt-in, default-off) games service, and
   defensive infra names — **kept
   byte-for-byte in lockstep** with `snapdragon-gateway`'s `CanonicalSeed`
   and `snapdragon-admin-bff-api`'s copy (all three must agree on the same
   70). This is defence-in-depth; Casdoor's own forbidden-username policy
   is the source gate.
2. **Federation confusable / availability gate** (defence-in-depth,
   **config-gated, default off**) — when
   `app.federation.username-check.enabled=true`, asks
   `snapdragon-federation-api`
   (`GET /api/federation/username/{candidate}/availability`, a
   ROLE_SERVICE call) to catch homoglyph / look-alike / reserved-
   confusable handles the exact local check can't see, and rejects `422`
   on a collision. The check **fails closed** — a federation outage
   rejects rather than mints a look-alike. The service bearer is minted
   via the OAuth2 client-credentials grant against systems Casdoor
   (reusing `app.casdoor.systems.*`) and cached/re-minted.
3. **Grant** — only when both gates pass, runs
   `GitHubOrgAdminGrantAction.evaluateAndGrant`; returns
   `{ memberId }` on success or `403` when the user has no Snapdragon
   org-admin reach. **When the master switch
   `app.actions.gh-org-admin.enabled` is `false` (the default), this step
   short-circuits and the endpoint returns `404`** — the reserved-name
   and confusable gates above still run, but no grant is ever attempted.

## Security & multi-tenancy

The service is defended in depth by two independent layers (full detail
in [SECURITY.md](SECURITY.md)):

- **OAuth2 resource server (application layer).** Validates Casdoor-
  issued JWTs from the systems Casdoor issuer. Path policy (reactive
  `SecurityConfig`):
  - `/actuator/health` + `/actuator/info` — public (probes);
  - `/actuator/**` — `ROLE_GAME-MASTER` (metrics / Prometheus / Casdoor
    reachability never leak);
  - `/api/registration/**` — `ROLE_ADMIN`;
  - SpringDoc spec + Swagger-UI (`/v3/api-docs/**`, `/swagger-ui/**`,
    `/swagger-ui.html`, `/webjars/swagger-ui/**`) — **permitAll under
    `dev`/`test`** (so the platform's `ng-openapi-gen gen:fetch` can curl
    the spec locally without a token) and **`ROLE_ADMIN` in prod**;
  - everything else — authenticated.
- **Mutual TLS (transport layer).** `server.ssl.*` is parameterized and
  **off by default** (local/dev/CI run plaintext). In-cluster the
  deployment mounts the PKCS12 keystore/truststore from the
  `snapdragon-api-mtls` ESO Secret and sets `SSL_ENABLED=true` +
  `SSL_CLIENT_AUTH=need`, so the TLS handshake requires a client cert
  chaining to the cluster CA — only services signed by the Vault/ESO PKI
  process (the be-gateway) can call this API at all.

### How this service fits the platform auth model

The platform's user-facing auth is an SSO flow: `login-ui` runs a Casdoor
OIDC authorization-code (PKCE) redirect, `user-bff` performs the
server-side code→token exchange and holds per-session JWT custody, and
the browser only ever holds an **opaque `bff_`-prefixed session** (the
end user never sees a Casdoor JWT). The `snapdragon-gateway` is a pure
Casdoor-JWT OAuth2 resource server: a pre-security exchange filter swaps
the `bff_` session for the internal custody JWT, then runs JWT validation
+ a data-driven role gate (`gateway_route_access` Postgres table via
`GatewayRouteAccessFilter`).

`snapdragon-actions` sits **behind** that gateway as another Casdoor-JWT
resource server. It is **not** part of the login/session path — it never
performs a code exchange, holds no session, and validates only the
fully-resolved internal JWT the gateway forwards. The login/session
relocation (admin-bff → user-bff) and the `snapdragon-auth-core`
extraction (shared account/session/MFA/Casdoor-admin infra, consumed by
user-bff + admin-bff only) therefore **do not touch this repo's code** —
it consumes neither. The one auth machinery it *does* share is the
stateless JWT→authority converter (`CasdoorAuthorities`, copied from
`snapdragon-gateway`) and the `ReservedNames` canonical-70 union (kept in
lockstep with `snapdragon-gateway` and `snapdragon-admin-bff-api`).

### Domain-scoping (issuer-keyed) — ✅ shipped

The platform's per-brand multi-tenancy keys a token's brand off its
**cryptographically-validated issuer** (one Casdoor per brand → distinct
`iss`). This service adopts the same canonical machinery the gateways
use, so it participates consistently. The pieces below are **part of the
service today** (merged via PR #12), and `SecurityConfig` already
delegates JWT → authority mapping to
`CasdoorAuthorities.converter(domainRegistry)` and swaps to the per-issuer
resolver only when the registry is genuinely multi-issuer:

- **`CasdoorAuthorities`** — the single canonical JWT → authority
  converter (copied from `snapdragon-gateway`), used by both the single-
  issuer and the optional multi-issuer decoder paths so they emit
  byte-identical authorities. It floors every principal to `ROLE_USER`,
  maps `roles` → `ROLE_<UPPER>`, passes `permissions` through **verbatim
  except** entries carrying a reserved prefix (`ROLE_` / `DOMAIN_`),
  which are **stripped** (authority-injection defence — a token can't
  forge a super-role or a tenant scope via a permission claim), honours
  legacy `isAdmin` → `ROLE_ADMIN`, and stamps **exactly one** issuer-
  derived `DOMAIN_<x>` authority.
- **`DomainRegistry`** — issuer-URI → brand map. **Backward-compatible:**
  with only the existing single `issuer-uri` it maps the lone issuer to
  `DOMAIN_default`, so this single-issuer cluster is byte-for-byte
  unchanged. When `app.tenancy.issuers` carries `issuer=domain` pairs it
  becomes the trusted allowlist and **fails closed** on an unknown `iss`.
- **`TenancyResourceServerConfig`** — config-gated, opt-in per-issuer
  resolver that 401s an unknown `iss` before any authority is emitted.
- **`Roles`** helpers — including the least-privilege cascade markers:
  `SUPERADMIN` (the only role exempt from per-domain scoping — the infra
  control plane) and the separate `IMAGE-BUILDER` capability.

**No per-query domain predicate is added here, by design.** This service
is a single-writer effector: its one request endpoint
(`/api/registration/github-org-admin/evaluate`, `ROLE_ADMIN`) mutates
fixed Casdoor instances + GitHub orgs regardless of the caller's brand,
and the reconcilers are scheduled (no request principal). It owns no
per-domain row store, so it gains the canonical converter + issuer-keyed
`DOMAIN_<x>` authority + multi-issuer back-compat — but there is no
domain-scoped query to filter, and none is invented.

### Roadmap — 🔭 planned (NOT shipped)

These platform-wide initiatives would touch this service when adopted.
They are **design-only today** *except where noted as ships-dark*; the
endpoint-enforcement and Boot-4 items below are not yet a current
capability on `main`.

- **Casdoor MFA / step-up (🔭 endpoint guard planned; converter parity
  ✅ ships dark).** A platform-wide design (9-PR plan) introduces
  WebAuthn + TOTP + recovery with `amr`/`acr` → `MFA_SATISFIED` and
  step-up enforcement on sensitive operations. The **converter half**
  already ships dark on `main`: `CasdoorAuthorities` derives the
  `MFA_SATISFIED` authority from the validated `mfa_satisfied_at` claim
  behind `app.mfa.enabled` (default `false`), `MFA_SATISFIED` is in the
  reserved-prefix injection defence, and `Roles` carries the markers
  (`CasdoorAuthoritiesMfaTest`). What is **still planned** is the
  *endpoint-enforcement guard* — an `mfa_satisfied` / `acr` step-up check
  guarding the `ROLE_ADMIN` registration endpoint; no such check exists
  yet, so the endpoint authorizes on `ROLE_ADMIN` alone.

## Current State

*(as of 2026-06-20)*

**Shipped & active on `main`:**

- The two cross-stack reconcilers (`GameMasterAdminSyncAction`,
  `GitHubOrgAdminReconcileAction`) and the single request endpoint
  (`/api/registration/github-org-admin/evaluate`), all defended by the
  OAuth2-resource-server + mTLS layers above.
- GitHub-org-admin auto-grant is **gated off by default**
  (`app.actions.gh-org-admin.enabled=false` → evaluate `404`, reconciler
  no-op).
- `OidcDefaultRoleProbeAction` (the scheduled OIDC-default-role caller for
  admin-bff's first-login hook) ships **dark / gated off by default**
  (`app.actions.oidc-default-roles.enabled=false` → tick returns
  immediately, role-hook client bean absent).
- MFA **converter parity** ships **dark / gated off by default**:
  `CasdoorAuthorities` derives the `MFA_SATISFIED` authority from the
  validated `mfa_satisfied_at` claim behind `app.mfa.enabled` (default
  `false` → marker never emitted), `MFA_SATISFIED` is in the converter's
  reserved-prefix injection defence, and `Roles` defines the
  `MFA_SATISFIED` / `mfa_satisfied_at` helpers (covered by
  `CasdoorAuthoritiesMfaTest`). The endpoint-enforcement guard is still
  🔭 planned (the `ROLE_ADMIN` endpoint authorizes on `ROLE_ADMIN` alone).
- Issuer-keyed domain-scoping (canonical `CasdoorAuthorities` +
  `DomainRegistry` multi-issuer back-compat, fail-closed on unknown
  `iss`), reserved-username gate (canonical 70 labels, in lockstep with
  `snapdragon-gateway` + `snapdragon-admin-bff-api`), the config-gated
  federation confusable gate, SpringDoc/Swagger surface, and the GitLab
  three-stage CI pipeline (test → build:image → pentest).

**Where this repo sits vs the platform auth model (2026 cycle):** the
platform SSO flow now runs `login-ui` → `user-bff` (server-side code
exchange + JWT custody) with the browser holding only an opaque `bff_`
session, and the gateway swapping `bff_` → the internal custody JWT
before its JWT validation + `gateway_route_access` role gate. Login /
session / account logic is being **relocated from `admin-bff` to
`user-bff`**, and the shared account/session/MFA/Casdoor-admin infra is
extracted into **`snapdragon-auth-core`** (consumed by user-bff +
admin-bff only). **None of that changes `snapdragon-actions`:** it is a
downstream resource server that only ever validates the resolved internal
JWT the gateway forwards; it performs no code exchange, holds no session,
and depends on neither `snapdragon-auth-core` nor the login path. The
SCG-5 route-namespace fix
(`spring.cloud.gateway.routes` → `…server.webflux.routes`) likewise does
**not** apply — this service is not a Spring Cloud Gateway and carries no
route config (its `application.yml` has no `spring.cloud.gateway.*`).

**Baseline:** this repo is on **Spring Boot 4.0.2** (Java 21) —
`pom.xml` inherits `org.springframework.boot:spring-boot-starter-parent:4.0.2`
directly, with Resilience4j `…-spring-boot4` 2.4.0 (+ `spring-boot-starter-aop`
so the `@CircuitBreaker` aspect actually registers) and SpringDoc 3.0.3 (with
`springdoc.api-docs.version: openapi_3_0` so the published spec stays OpenAPI
3.0 for ng-openapi-gen 0.51.0). The Boot-4 WebFlux JSON codec is Jackson 3.

**Remaining follow-up:** this service still inherits
`spring-boot-starter-parent` **directly** rather than consuming
`snapdragon-parent` (metapom 2.0.0) or `snapdragon-commons` (2.0.0) — whereas
the rest of the Java estate (e.g. `federation-api`) runs on
`snapdragon-parent` + `snapdragon-commons`. The `CasdoorAuthorities` / `Roles`
/ `DomainRegistry` classes here are still local copies rather than
`systems.snapdragon.commons.*` imports; adopting the metapom + commons is the
outstanding consolidation step.

## Roadmap

Platform-wide, designed-or-in-flight initiatives, called out where they
specifically touch this effector. Nothing below is a current capability
on `main`.

- **Adopt the unified Boot 4 baseline (🔭 follow-up).** Migrate `pom.xml`
  to `snapdragon-parent` (Boot 4.0.2 / Spring Cloud 2025.1) and fold the
  local `CasdoorAuthorities` / `Roles` / `DomainRegistry` copies onto the
  shared `snapdragon-commons` (`systems.snapdragon.commons.*`) security/
  tenancy types — bringing this repo in line with the already-migrated
  services and removing the byte-for-byte-copy maintenance burden.
- **Casdoor MFA / step-up (🔭 endpoint guard planned).** WebAuthn + TOTP +
  recovery, per-role defaults, `amr`/`acr` → `MFA_SATISFIED`. The
  `MFA_SATISFIED` converter parity already **ships dark** here (behind
  `app.mfa.enabled`); what remains planned is the `mfa_satisfied` / `acr`
  step-up guard on the `ROLE_ADMIN` registration endpoint (today it
  authorizes on `ROLE_ADMIN` alone). See the planned block above.
- **SSO end-to-end live + e2e (in progress, platform-level).** Does not
  change this service's code, but the cross-stack admin-sync and
  per-role access matrix exercised here are part of the platform e2e
  story.
- **OWASP pen-test + nuclei in CI (✅ wired here, expanding).** The
  `pentest` stage (SCA + authenticated ZAP baseline + nuclei) already
  runs on this repo via the centralized `snapdragon-generator` templates;
  template hardening continues platform-wide.

Other in-flight platform initiatives — encrypted versioned backups
(Velero + kopia/restic), egress VPN drop-in, per-node attached storage,
desktop app governance, workload console, device-license registry,
multi-tenancy superadmin (per-domain CA + per-brand gateway/BFF/UI), the
games app-store, and A/V via coturn/TURN — are owned by other repos and
do not change this effector's code.

## Metrics

Prometheus is exposed at `/actuator/prometheus` (gated to
`ROLE_GAME-MASTER`).

| Name | Type | Meaning |
|---|---|---|
| `actions.gm_sync.added` | counter | Members added to games-admin during a sync |
| `actions.gm_sync.removed` | counter | Members removed from games-admin during a sync |
| `actions.gm_sync.unchanged` | counter | Sync ticks where membership already matched (no write) |
| `actions.gm_sync.errors` | counter | Sync ticks that failed to read or write Casdoor |
| `actions.gh_org_admin.granted` | counter | Users granted systems-admin via GitHub org membership |
| `actions.gh_org_admin.revoked` | counter | Users stripped of systems-admin after losing GitHub reach |
| `actions.gh_org_admin.denied` | counter | Sign-up evaluate requests rejected (no Snapdragon org admin) |
| `actions.gh_org_admin.errors` | counter | Sign-up evaluations that errored before a decision |
| `actions.gh_org_admin.reconciled` | counter | Admins re-evaluated against GitHub on a reconcile tick |
| `actions.gh_org_admin.reconcile_skipped` | counter | Admins left untouched for lack of a fresh GitHub token |
| `actions.gh_org_admin.reconcile_errors` | counter | Reconcile ticks / per-user checks that failed |
| `actions.oidc_default_roles.applied` | counter | New users for whom OIDC-provider default roles were applied via admin-bff (default-off) |
| `actions.oidc_default_roles.skipped` | counter | New users skipped (already seen / unmapped provider / outside lookback) |
| `actions.oidc_default_roles.errors` | counter | OIDC default-role probe ticks / per-user applies that failed |

## Configuration

Every Casdoor endpoint + credential, the GitHub orgs/role, the schedule
intervals, the token TTL, the federation toggle, and the mTLS stores are
env-var driven — see `src/main/resources/application.yml` for the full
list of keys and defaults. Highlights:

| Concern | Key (env override) | Default |
|---|---|---|
| Systems Casdoor | `app.casdoor.systems.*` (`CASDOOR_SYSTEMS_URL`, `…CLIENT_ID`, `…CLIENT_SECRET`, `…ORG`) | `…snapdragon-systems` |
| Games Casdoor | `app.casdoor.games.*` (`CASDOOR_GAMES_URL`, …, `CASDOOR_GAMES_ORG`) | `…threewizards` |
| JWT issuer | `spring.security.oauth2.resourceserver.jwt.issuer-uri` (`CASDOOR_SYSTEMS_URL`) | systems Casdoor |
| GM sync interval | `app.actions.game-master-sync.interval-ms` (`GM_SYNC_INTERVAL_MS`) | `60000` |
| GitHub-org-admin auto-grant | `app.actions.gh-org-admin.enabled` (`GH_ORG_ADMIN_ENABLED`) | `false` (off → evaluate returns `404`, reconciler no-op) |
| GitHub orgs | `app.actions.gh-org-admin.orgs` (`GH_ORG_ADMIN_ORGS`) | `Snapdragon-Systems,Snapdragon` |
| Granted systems role | `app.actions.gh-org-admin.systems-admin-role` (`GH_ORG_ADMIN_ROLE`) | `admin` |
| GitHub token TTL | `app.actions.gh-org-admin.token-ttl` (`GH_ORG_ADMIN_TOKEN_TTL`) | `PT24H` |
| Revoke reconcile interval | `app.actions.gh-org-admin.reconcile.interval-ms` | `21600000` (6 h) |
| OIDC default-role probe | `app.actions.oidc-default-roles.enabled` (`APP_OIDC_DEFAULT_ROLES_ENABLED`) | `false` (off → tick returns immediately, role-hook bean absent) |
| OIDC probe interval | `app.actions.oidc-default-roles.interval-ms` | `300000` (5 min) |
| MFA converter parity | `app.mfa.enabled` | `false` (off → `MFA_SATISFIED` marker never emitted) |
| Federation confusable gate | `app.federation.username-check.enabled` (`APP_FEDERATION_USERNAME_CHECK_ENABLED`) | `false` |
| GitHub API base | `app.github.base-url` (`GITHUB_API_BASE_URL`) | `https://api.github.com` |
| mTLS | `server.ssl.*` (`SSL_ENABLED`, `SSL_CLIENT_AUTH`, …) | off / `none` |

Resilience4j circuit breakers are configured per downstream
(`casdoor`, `github`, `federation`) under `resilience4j.circuitbreaker.*`.

## API docs

SpringDoc publishes the OpenAPI 3 spec at `/v3/api-docs` and Swagger-UI
at `/swagger-ui.html`, scoped to this service's own `web` package. This
is the source the platform's `ng-openapi-gen` typed-clients, the admin
api-docs/SwaggerUI federated module, and the docs pipeline consume. The
spec paths are public under `dev`/`test` and `ROLE_ADMIN` in prod (see
above).

## Running locally

```bash
docker compose up -d                       # two Casdoors (systems + games) + the action
docker compose logs -f snapdragon-actions  # watch ticks
curl localhost:8090/actuator/health        # public probe
```

The compose stack brings up two Casdoor instances (each with its own
Postgres) so the game-master → admin sync can be exercised end-to-end
without production endpoints. The action service itself is stateless —
restarting it replays the next tick against live Casdoor state.

## Build & CI

A standard Spring Boot Maven build (`./mvnw package`); the multi-stage
`Dockerfile` produces a slim JRE runtime image. CI is **GitLab**
(`.gitlab-ci.yml`): the GitHub repo is pull-mirrored into the in-cluster
GitLab, which runs the pipeline on push / mirror-sync. Images carry no
keys — PKI material is supplied from the USB at first boot.

The pipeline has three stages (`stages: [test, build, pentest]`):

1. **`test`** (✅ shipped) — runs `./mvnw verify -B` inside an
   `eclipse-temurin:21-jdk-noble` container on the default branch / merge
   requests / tags, publishing the JUnit surefire reports as artifacts.
2. **`build:image`** (✅ shipped) — `needs: [test]`; builds and pushes the
   container image to the in-cluster registry on the default branch / on
   tag, tagged with the release tag or short SHA plus a moving branch tag.
3. **`pentest`** (✅ shipped) — the continuous-OWASP stage. Thin
   `pentest:sca` / `pentest:dast` jobs `extends:` the centralized hidden
   `.pentest-sca` / `.pentest-dast-baseline` templates `include`d from
   `snapdragon-generator` (`/tests/pentest/ci/pentest-stage.gitlab-ci.yml`),
   so SCA (OWASP Dependency-Check) and DAST (authenticated ZAP baseline +
   probes + nuclei) share one source of truth across every onboarded repo.
   Both run on the default branch / merge requests with
   `allow_failure: true` so findings surface in the MR security widget
   without blocking the pipeline. `make pentest` runs the same posture
   locally as a pre-push gate (see the `Makefile`).

## Adding new actions

Each action is a `@Service` with a `@Scheduled` method;
`GameMasterAdminSyncAction` is the reference shape. Wrap any external
side-effect in a Resilience4j `@CircuitBreaker(name=…)` so a slow
downstream doesn't pile up tick work, and emit per-action Micrometer
counters for what was applied / skipped / errored.

## Native image (GraalVM)

The build carries a `native` profile (same shape as `snapdragon-gateway`)
plus app-specific AOT reflection hints in `NativeHintsConfig` for the
JSON-bound DTOs that cross the HTTP boundary and the
`@CircuitBreaker`-advised action beans:

```bash
./mvnw -Pnative native:compile   # requires a GraalVM native-image toolchain
```

The AOT pipeline (`./mvnw -Pnative spring-boot:process-aot`) is verified;
run `native:compile` on a GraalVM JDK to produce the binary.
