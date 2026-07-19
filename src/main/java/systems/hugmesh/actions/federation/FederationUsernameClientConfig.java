package systems.hugmesh.actions.federation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Wires the {@link FederationUsernameClient} the registration confusable gate
 * calls — a ROLE_SERVICE WebClient against hugmesh-federation-api's username
 * availability endpoint.
 *
 * <p><b>GATED, DEFAULT OFF.</b> The bean is only created when
 * {@code app.federation.username-check.enabled=true} — same shape as the other
 * internal-call gates across the platform. Local / dev / CI (and every existing
 * test) leave it off, so the {@code RegistrationController} keeps running its
 * local exact reserved fast-path ONLY, with no federation dependency. In-cluster
 * the deployment flips {@code APP_FEDERATION_USERNAME_CHECK_ENABLED=true} so the
 * confusable engine runs on every grant.
 *
 * <p><b>Service bearer.</b> The availability endpoint is INTERNAL / ROLE_SERVICE
 * gated, so this caller presents a Casdoor SERVICE JWT minted via the OAuth2
 * client-credentials grant against hugmesh.systems Casdoor — reusing the same
 * {@code app.casdoor.systems.{base-url,client-id,client-secret}} credentials the
 * service already holds for the admin API. The token is cached and re-minted
 * shortly before expiry (mirrors hugmesh-remote-fragment's CasdoorTokenClient).
 *
 * <p><b>Base URL</b> defaults to the in-cluster DNS of federation-api,
 * env-overridable via {@code FEDERATION_API_BASE_URL}.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.federation.username-check", name = "enabled", havingValue = "true")
public class FederationUsernameClientConfig {

    /** Re-mint this long before stated expiry so a call never races the clock. */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);
    /** Fallback lifetime if Casdoor omits expires_in. */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    @Bean
    public FederationUsernameClient federationUsernameClient(
            @Value("${federation.api.base-url:http://hugmesh-federation-api.hugmesh-backend.svc.cluster.local:8080}")
            String baseUrl,
            @Value("${app.casdoor.systems.base-url}") String casdoorBaseUrl,
            @Value("${app.casdoor.systems.client-id}") String clientId,
            @Value("${app.casdoor.systems.client-secret}") String clientSecret) {

        WebClient federation = WebClient.builder().baseUrl(baseUrl).build();
        WebClient casdoor = WebClient.builder().baseUrl(casdoorBaseUrl).build();

        Supplier<Mono<String>> tokenSupplier =
                serviceTokenSupplier(casdoor, clientId, clientSecret);

        return new FederationUsernameClient(federation, tokenSupplier);
    }

    /**
     * A caching client-credentials token supplier. Holds the last minted token +
     * the instant it should be considered stale, and re-mints only when stale.
     *
     * <p>Public + reused: the OIDC default-role registration hook
     * ({@code OidcRoleHookClientConfig}) mints its admin-bff ROLE_SERVICE
     * bearer through this exact same supplier against the same
     * {@code app.casdoor.systems.*} credentials — one canonical
     * client-credentials mint+cache for every internal ROLE_SERVICE caller
     * in this service.
     */
    public static Supplier<Mono<String>> serviceTokenSupplier(WebClient casdoor, String clientId, String clientSecret) {
        final AtomicReference<Cached> cache = new AtomicReference<>(null);
        return () -> Mono.defer(() -> {
            Cached c = cache.get();
            if (c != null && Instant.now().isBefore(c.staleAt())) {
                return Mono.just(c.token());
            }
            return mint(casdoor, clientId, clientSecret, cache);
        });
    }

    private static Mono<String> mint(WebClient casdoor, String clientId, String clientSecret,
                                     AtomicReference<Cached> cache) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return casdoor.post()
                .uri("/api/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .flatMap(json -> {
                    Object at = json.get("access_token");
                    if (!(at instanceof String s) || s.isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "casdoor token mint returned no access_token"));
                    }
                    Object exp = json.get("expires_in");
                    long expiresIn = exp instanceof Number n ? n.longValue() : 0L;
                    Duration ttl = expiresIn > 0 ? Duration.ofSeconds(expiresIn) : DEFAULT_TTL;
                    cache.set(new Cached(s, Instant.now().plus(ttl).minus(EXPIRY_SKEW)));
                    return Mono.just(s);
                });
    }

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP_TYPE =
            (Class<Map<String, Object>>) (Class<?>) Map.class;

    private record Cached(String token, Instant staleAt) { }
}
