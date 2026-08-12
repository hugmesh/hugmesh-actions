package net.hugmesh.actions.federation;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Reactive ROLE_SERVICE client of hugmesh-federation-api's username
 * availability / confusable endpoint. This is the registration flow's
 * defence-in-depth confusable gate: federation owns the look-alike skeleton
 * index + reserved-skeleton set, so it catches homoglyph / look-alike /
 * reserved-confusable usernames the caller's purely-exact local reserved
 * fast-path cannot.
 *
 * <p><b>Auth.</b> The availability endpoint is INTERNAL / ROLE_SERVICE gated on
 * the federation side (it is an enumeration oracle, never public). This client
 * therefore presents a Casdoor SERVICE bearer — minted via the OAuth2
 * client-credentials grant (see {@code FederationUsernameClientConfig}) and
 * forwarded on every call — NOT the end user's token. The token supplier
 * caches + re-mints, so this client just asks for "a current bearer" per call.
 *
 * <p><b>Endpoint.</b> {@code GET /api/federation/username/{candidate}/availability}
 * -> {@link UsernameAvailability} (exact/confusable/reserved verdict). Path
 * variable is URL-encoded by the {@link WebClient} {@code uri(...)} builder.
 *
 * <p><b>Fail-closed.</b> Per the design, the confusable engine lives ONLY in
 * federation, so a federation outage during signup must FAIL CLOSED for the
 * confusable check — a short timeout + the circuit breaker bound the cost, and
 * any error resolves to {@link UsernameAvailability#collideUnknown()} (collide
 * = true) rather than waving the candidate through. The caller's local exact
 * reserved fast-path still runs first, so reserved labels are rejected even
 * when federation is down.
 */
public class FederationUsernameClient {

    private static final Logger log = LoggerFactory.getLogger(FederationUsernameClient.class);

    /** Bound the availability call so a slow federation never stalls a signup. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient http;
    private final Supplier<Mono<String>> serviceToken;

    /**
     * @param http         WebClient pre-pointed at federation-api's base URL
     *                     (and carrying the cluster-CA mTLS connector when enabled).
     * @param serviceToken supplies a current Casdoor service bearer (cached / re-minted).
     */
    public FederationUsernameClient(WebClient http, Supplier<Mono<String>> serviceToken) {
        this.http = http;
        this.serviceToken = serviceToken;
    }

    /**
     * Ask federation whether {@code candidate} is available, or collides with an
     * existing username (exact / confusable) or a reserved label. Never errors:
     * a transport / status / timeout failure resolves to a collide=true verdict
     * (fail-closed) so the caller rejects rather than mints a look-alike when the
     * registry can't be reached.
     */
    @CircuitBreaker(name = "federation", fallbackMethod = "failClosed")
    public Mono<UsernameAvailability> availability(String candidate) {
        return serviceToken.get()
                .flatMap(token -> http.get()
                        .uri(uri -> uri.path("/api/federation/username/{candidate}/availability")
                                .build(candidate))
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToMono(UsernameAvailability.class))
                .timeout(CALL_TIMEOUT)
                .defaultIfEmpty(UsernameAvailability.collideUnknown())
                .onErrorResume(e -> {
                    log.warn("[federation-username] availability check for '{}' failed, "
                            + "failing CLOSED (collide): {}", candidate, e.toString());
                    return Mono.just(UsernameAvailability.collideUnknown());
                });
    }

    /**
     * Circuit-breaker fallback (open circuit) — same fail-closed verdict so a
     * federation brown-out can never let a confusable username through.
     */
    @SuppressWarnings("unused")
    private Mono<UsernameAvailability> failClosed(String candidate, Throwable t) {
        log.warn("[federation-username] availability circuit OPEN for '{}', failing CLOSED: {}",
                candidate, t.toString());
        return Mono.just(UsernameAvailability.collideUnknown());
    }
}
