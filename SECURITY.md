# Security — hugmesh-actions

> In-cluster this service is deployed under the name **`hugmesh-api`**
> (`hugmesh-generator/specs/cluster/manifests/hugmesh-apis/deployment.yaml`
> runs the actions image as `hugmesh-api`), so its mTLS identity is the
> `hugmesh-api-mtls` ESO Secret.

## Authentication layers

This service is defended in depth by two independent layers:

1. **OAuth2 resource server (application layer).** Requests to
   `/api/registration/**` must carry a valid JWT from the systems Casdoor
   (`spring.security.oauth2.resourceserver`, the canonical issuer for this
   surface). This is unchanged.

2. **Mutual TLS — the API-to-API trust boundary (transport layer).** In-cluster,
   the TLS handshake **requires a client certificate that chains to the cluster CA
   truststore**. Only services that were deployed and signed by our Vault/ESO PKI
   process hold such a certificate. A random pod, an `io`/external client, or
   anything not issued by our PKI presents no chaining cert, so its handshake is
   rejected before any byte of HTTP is read — it cannot read or call this API at all.

## How the mTLS layer is wired

The cluster CA is the offline root from `hugmesh-generator/scripts/gen-keystores.sh`.
In-cluster, the Vault `pki_int` engine, which chains to that root, issues a leaf
cert for the app (`pki_int/issue/hugmesh-api`, ttl `720h`). External Secrets
Operator (ESO) syncs it into the `hugmesh-api-mtls` `kubernetes.io/tls` Secret
(`tls.crt` / `tls.key` / `ca.crt`, where `ca.crt` is the issuing CA) and
refreshes it every `168h`. A keystore initContainer (`build-keystore`, the
equivalent of the gateway chart's) assembles:

- `keystore.p12` — this service's own mTLS **identity** (key + cert chain),
- `truststore.p12` — the **cluster CA** trust anchor,

into an in-pod tmpfs and generates the store password there (it never enters git,
a Secret, or Vault). It writes the password to `/stores/application.properties`,
which the pod loads through `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/stores/`.
The deployment also sets the `SSL_*` env vars below, so the running pod enforces
client-cert verification. Every in-cluster leaf chains to the same cluster CA, so
the truststore validates them.

The gateway (`hugmesh-be-gateway`) is the legitimate caller: it presents its own
ESO-issued client cert when proxying to this API (the `hugmesh-api` route in
`specs/cluster/apps/20-gateway-be.yaml` uses `https://`), so its handshake succeeds
while everyone else's fails.

## Configuration (parameterized — safe by default)

`server.ssl.*` in `application.yml` is parameterized so local/dev/CI keep working:

| Property | Env var | Local/CI default | In-cluster value |
| --- | --- | --- | --- |
| `server.ssl.enabled` | `SSL_ENABLED` | `false` (plaintext) | `true` |
| `server.ssl.client-auth` | `SSL_CLIENT_AUTH` | `none` | `need` |
| `server.ssl.key-store` | `SSL_KEY_STORE` | `/stores/keystore.p12` | mounted PKCS12 keystore |
| `server.ssl.key-store-type` | `SSL_KEY_STORE_TYPE` | `PKCS12` | `PKCS12` |
| `server.ssl.key-store-password` | `SSL_KEY_STORE_PASSWORD` | _(empty)_ | in-pod-generated, from `/stores/application.properties` |
| `server.ssl.trust-store` | `SSL_TRUST_STORE` | `/stores/truststore.p12` | mounted PKCS12 truststore (cluster CA) |
| `server.ssl.trust-store-type` | `SSL_TRUST_STORE_TYPE` | `PKCS12` | `PKCS12` |
| `server.ssl.trust-store-password` | `SSL_TRUST_STORE_PASSWORD` | _(empty)_ | in-pod-generated, from `/stores/application.properties` |
| `server.ssl.enabled-protocols` | `SSL_ENABLED_PROTOCOLS` | `TLSv1.3` | `TLSv1.3` |

With the defaults, `server.ssl.enabled=false`, so the stores are never opened and
existing tests run unchanged. `client-auth` maps to Netty's `ClientAuth`:
`none → NONE`, `want → OPTIONAL`, `need → REQUIRE`. WebFlux/Netty reads these
properties directly; no custom `SslContext`/`HttpServer` bean is needed.

## Deployment requirement (owned by hugmesh-generator)

The deployment manifest for this service lives in `hugmesh-generator`
(`specs/cluster/manifests/hugmesh-apis/deployment.yaml`, name `hugmesh-api`),
not in this repo. On origin/main today it already enforces mTLS. It has:

- the `build-keystore` initContainer, plus the `pki` (from `hugmesh-api-mtls`)
  and `stores` (tmpfs) volumes, as
  `specs/cluster/charts/hugmesh-gateway/templates/deployment.yaml` does;
- these env vars:

```
SSL_ENABLED=true
SSL_CLIENT_AUTH=need
SSL_KEY_STORE=/stores/keystore.p12
SSL_KEY_STORE_TYPE=PKCS12
SSL_TRUST_STORE=/stores/truststore.p12
SSL_TRUST_STORE_TYPE=PKCS12
SPRING_CONFIG_ADDITIONAL_LOCATION=file:/stores/
```

The two store passwords are not env vars. They come from
`/stores/application.properties`, written by the initContainer.

Note: this service listens on port 8090 by default. The in-cluster Service
`hugmesh-api` listens on 8080 and forwards to the container's 8090. The
`client-auth=need` setting rejects a kubelet `httpGet` probe, so the deployment's
readiness probe is an `exec` probe that runs `curl` with the pod's own client cert
against `https://hugmesh-api:8090/actuator/health/readiness`. The deployment
defines no liveness probe. The be-gateway presents its client cert and calls this
API over `https://`.

## Roadmap — 🔭 planned (NOT shipped)

- **Casdoor MFA / step-up.** A platform-wide design (WebAuthn + TOTP +
  recovery; `amr`/`acr` → `MFA_SATISFIED`; step-up on sensitive operations) is
  design-complete. The converter half ships in this service, switched off: with
  `app.mfa.enabled=true`, `CasdoorAuthorities` (from `hugmesh-commons`) derives an
  `MFA_SATISFIED` authority from a fresh `mfa_satisfied_at` claim
  (`app.mfa.freshness-window`, default 600000 ms). The default is `false`, so
  the marker is never emitted. The endpoint guard is **not implemented**. When
  adopted, the `ROLE_ADMIN` registration endpoint would gain an `MFA_SATISFIED`
  check on top of `ROLE_ADMIN`. Today the endpoint authorizes on `ROLE_ADMIN`
  alone — there is no MFA/`acr` check in the code.
