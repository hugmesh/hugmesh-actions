# Security — snapdragon-actions

> In-cluster this service is deployed under the name **`snapdragon-api`**
> (`snapdragon-generator/specs/cluster/manifests/snapdragon-apis/deployment.yaml`
> runs the actions image as `snapdragon-api`), so its mTLS identity is the
> `snapdragon-api-mtls` ESO Secret.

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

The cluster CA is the offline root from `snapdragon-generator/scripts/gen-keystores.sh`.
In-cluster, Vault PKI imports that CA and issues a short-lived leaf cert per app;
External Secrets Operator (ESO) syncs it into the `snapdragon-api-mtls`
`kubernetes.io/tls` Secret (`tls.crt` / `tls.key` / `ca.crt`). A keystore
initContainer (the equivalent of the gateway chart's `build-keystore`) assembles:

- `keystore.p12` — this service's own mTLS **identity** (key + cert chain),
- `truststore.p12` — the **cluster CA** trust anchor,

into an in-pod tmpfs and generates the store password there (it never enters git,
a Secret, or Vault). The deployment then sets the env below so the running pod
enforces client-cert verification. Because every in-cluster leaf chains to the same
cluster CA, the truststore validates them; ESO rotates the cert before expiry.

The gateway (`snapdragon-be-gateway`) is the legitimate caller: it presents its own
ESO-issued client cert when proxying to this API, so its handshake succeeds while
everyone else's fails.

## Configuration (parameterized — safe by default)

`server.ssl.*` in `application.yml` is parameterized so local/dev/CI keep working:

| Property | Env var | Local/CI default | In-cluster value |
| --- | --- | --- | --- |
| `server.ssl.enabled` | `SSL_ENABLED` | `false` (plaintext) | `true` |
| `server.ssl.client-auth` | `SSL_CLIENT_AUTH` | `none` | `need` |
| `server.ssl.key-store` | `SSL_KEY_STORE` | `/stores/keystore.p12` | mounted PKCS12 keystore |
| `server.ssl.key-store-type` | `SSL_KEY_STORE_TYPE` | `PKCS12` | `PKCS12` |
| `server.ssl.key-store-password` | `SSL_KEY_STORE_PASSWORD` | _(empty)_ | in-pod-generated |
| `server.ssl.trust-store` | `SSL_TRUST_STORE` | `/stores/truststore.p12` | mounted PKCS12 truststore (cluster CA) |
| `server.ssl.trust-store-type` | `SSL_TRUST_STORE_TYPE` | `PKCS12` | `PKCS12` |
| `server.ssl.trust-store-password` | `SSL_TRUST_STORE_PASSWORD` | _(empty)_ | in-pod-generated |
| `server.ssl.enabled-protocols` | `SSL_ENABLED_PROTOCOLS` | `TLSv1.3` | `TLSv1.3` |

With the defaults, `server.ssl.enabled=false`, so the stores are never opened and
existing tests run unchanged. `client-auth` maps to Netty's `ClientAuth`:
`none → NONE`, `want → OPTIONAL`, `need → REQUIRE`. WebFlux/Netty reads these
properties directly; no custom `SslContext`/`HttpServer` bean is needed.

## Deployment requirement (owned by snapdragon-generator)

The deployment manifest for this service lives in `snapdragon-generator`
(`specs/cluster/manifests/snapdragon-apis/deployment.yaml`, name `snapdragon-api`),
not in this repo. To turn enforcement on, that deployment must (a) add the keystore
initContainer + `pki` (from `snapdragon-api-mtls`) and `stores` (tmpfs) volumes —
exactly as `charts/snapdragon-gateway/templates/deployment.yaml` does — and (b) set:

```
SSL_ENABLED=true
SSL_CLIENT_AUTH=need
SSL_KEY_STORE=/stores/keystore.p12
SSL_KEY_STORE_TYPE=PKCS12
SSL_KEY_STORE_PASSWORD=<in-pod generated>
SSL_TRUST_STORE=/stores/truststore.p12
SSL_TRUST_STORE_TYPE=PKCS12
SSL_TRUST_STORE_PASSWORD=<in-pod generated>
```

Note: this service listens on port 8090 by default but the in-cluster deployment
maps it to 8080. The readiness/liveness probes must switch to HTTPS
(`scheme: HTTPS`) once `SSL_ENABLED=true`, and the be-gateway must present its
client cert AND call this API over `https://` for the route to keep working.

## Roadmap — 🔭 planned (NOT shipped)

- **Casdoor MFA / step-up.** A platform-wide design (WebAuthn + TOTP +
  recovery; `amr`/`acr` → `MFA_SATISFIED`; step-up on sensitive operations) is
  design-complete but **not yet implemented in this service**. When adopted, the
  `ROLE_ADMIN` registration endpoint would gain an `mfa_satisfied` / `acr`
  authority check on top of `ROLE_ADMIN`, layered through the shared
  `CasdoorAuthorities` / `Roles` converter. Today the endpoint authorizes on
  `ROLE_ADMIN` alone — there is no MFA/`acr` check in the code.
