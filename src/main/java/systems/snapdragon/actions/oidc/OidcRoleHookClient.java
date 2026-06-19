package systems.snapdragon.actions.oidc;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reactive ROLE_SERVICE client of snapdragon-admin-bff-api's
 * {@code POST /bff/internal/oidc/first-login} endpoint — the EXISTING
 * (already-built) path that reads a domain+provider's default-role map
 * and applies each role to the Casdoor user. This client is the missing
 * <em>caller</em> of that endpoint: the OIDC default-role registration
 * probe invokes {@link #applyDefaultRoles} once per genuinely-new user.
 *
 * <p><b>Auth.</b> The endpoint is INTERNAL / {@code @PreAuthorize("hasRole('SERVICE')")}
 * on the admin-bff side. This client therefore presents a Casdoor SERVICE
 * bearer — minted via the OAuth2 client-credentials grant (see
 * {@link OidcRoleHookClientConfig}) and forwarded on every call — NOT an
 * end-user token. The token supplier caches + re-mints, so this client
 * just asks for "a current bearer" per call (same shape as
 * {@code FederationUsernameClient}).
 *
 * <p><b>Request.</b> JSON {@code {domain, provider, casdoorUsername}}
 * matching the admin-bff {@code FirstLoginRequest} record (all three
 * {@code @NotBlank}). Response: {@link FirstLoginResponse}.
 *
 * <p><b>Fails OPEN to a no-op.</b> Unlike the federation confusable gate
 * (which fails CLOSED), a probe error here must NEVER fabricate roles and
 * must NEVER abort the tick — role assignment is a best-effort,
 * next-tick-retried reconcile (the probe re-evaluates on a later tick;
 * {@code addUserToRole} is itself idempotent). So any 5xx / timeout /
 * transport error resolves to an empty {@link FirstLoginResponse}
 * (no roles claimed), bounded by a short timeout + the circuit breaker.
 */
public class OidcRoleHookClient {

    private static final Logger log = LoggerFactory.getLogger(OidcRoleHookClient.class);

    /** Bound the call so a slow admin-bff never stalls a probe tick. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private static final FirstLoginResponse NO_OP = new FirstLoginResponse(List.of());

    private final WebClient http;
    private final Supplier<Mono<String>> serviceToken;

    /**
     * @param http         WebClient pre-pointed at admin-bff's base URL
     *                     (and carrying the cluster-CA mTLS connector when enabled).
     * @param serviceToken supplies a current Casdoor service bearer (cached / re-minted).
     */
    public OidcRoleHookClient(WebClient http, Supplier<Mono<String>> serviceToken) {
        this.http = http;
        this.serviceToken = serviceToken;
    }

    /**
     * Apply the configured OIDC-provider default roles for a newly-created
     * user by calling the admin-bff first-login endpoint. Never errors: a
     * transport / status / timeout failure resolves to an empty
     * {@link FirstLoginResponse} (fail-open, no roles claimed) so a probe
     * error can never break the tick nor fabricate a grant.
     */
    @CircuitBreaker(name = "admin-bff", fallbackMethod = "failOpen")
    public Mono<FirstLoginResponse> applyDefaultRoles(String domain, String provider, String username) {
        Map<String, String> body = Map.of(
                "domain", domain,
                "provider", provider,
                "casdoorUsername", username);
        return serviceToken.get()
                .flatMap(token -> http.post()
                        .uri("/bff/internal/oidc/first-login")
                        .headers(h -> h.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(FirstLoginResponse.class))
                .timeout(CALL_TIMEOUT)
                .defaultIfEmpty(NO_OP)
                .onErrorResume(e -> {
                    log.warn("[oidc-role-hook] first-login for domain={} provider={} user={} "
                            + "failed, failing OPEN (no-op): {}", domain, provider, username, e.toString());
                    return Mono.just(NO_OP);
                });
    }

    /**
     * Circuit-breaker fallback (open circuit) — same fail-open no-op so a
     * brown-out of admin-bff can never abort the probe tick. The probe
     * re-evaluates this user on a later tick.
     */
    @SuppressWarnings("unused")
    private Mono<FirstLoginResponse> failOpen(String domain, String provider, String username, Throwable t) {
        log.warn("[oidc-role-hook] admin-bff circuit OPEN for domain={} provider={} user={}, "
                + "failing OPEN (no-op): {}", domain, provider, username, t.toString());
        return Mono.just(NO_OP);
    }
}
