package net.hugmesh.actions.notify;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Reactive ROLE_SERVICE client of hugmesh-user-bff-api's INTERNAL in-app
 * notification deliver endpoint — the actions-side half of the platform's
 * notifications v1 write path. user-bff's {@code user_notifications} store has
 * exactly ONE production writer: this service calling
 * {@code POST /bff/user/internal/notifications}.
 *
 * <p><b>Auth.</b> The deliver endpoint sits under user-bff's
 * {@code /bff/user/internal/**} prefix, gated to {@code ROLE_SERVICE}
 * EXCLUSIVELY (no human JWT can reach it — the target user comes from the
 * request body, so a human caller could spoof notifications to anyone). This
 * client therefore presents a Casdoor SERVICE bearer minted via the OAuth2
 * client-credentials grant (see {@code UserNotificationClientConfig}) — the
 * same mint+cache supplier the federation username client uses.
 *
 * <p><b>Contract</b> (mirrors user-bff's {@code InternalNotificationController
 * .DeliverRequest} / {@code DeliveryResult} field-for-field):
 * <ul>
 *   <li>target {@code (domain, userSubject)} — userSubject is the target's
 *       JWT subject (the Casdoor user UUID, NOT the username; resolve via
 *       {@code CasdoorClient.getUserId});</li>
 *   <li>{@code category} — user-bff's fixed vocabulary
 *       ({@code account-restriction | role-change | infra-impacting |
 *       feature-availability}); anything else is rejected 400 fail-closed;</li>
 *   <li>{@code sourceTable}+{@code sourceId} — identify the source event and
 *       drive user-bff's deterministic idempotent row id, so a retried
 *       delivery of the same event dedupes ({@code deduped=true}) instead of
 *       double-writing.</li>
 * </ul>
 *
 * <p>Errors PROPAGATE — the fail-soft policy (a lost notification must never
 * fail a reconcile tick) lives in {@link RoleChangeNotifier}, keeping this
 * client honestly thin and its outcomes testable.
 */
public class UserNotificationClient {

    /** Bound the deliver call so a slow user-bff never stalls a reconcile tick. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient http;
    private final Supplier<Mono<String>> serviceToken;

    /**
     * @param http         WebClient pre-pointed at user-bff-api's base URL.
     * @param serviceToken supplies a current Casdoor service bearer (cached / re-minted).
     */
    public UserNotificationClient(WebClient http, Supplier<Mono<String>> serviceToken) {
        this.http = http;
        this.serviceToken = serviceToken;
    }

    /** Deliver request — field-for-field mirror of user-bff's {@code DeliverRequest}. */
    public record Delivery(String domain, String userSubject, String category,
                           String title, String body,
                           String sourceTable, String sourceId) {}

    /** Deliver outcome — mirror of user-bff's {@code DeliveryResult}.
     *  {@code delivered=false, reason=suppressed} means the target has
     *  silenced the category (an honest non-delivery, not an error);
     *  {@code deduped=true} means the same source event was already
     *  delivered (retry hit the idempotent row id). */
    public record Outcome(boolean delivered, boolean deduped, String reason) {}

    /**
     * POST one in-app notification to user-bff's internal deliver endpoint.
     * Emits the BFF's honest {@link Outcome}; transport/status failures and
     * the {@value #CALL_TIMEOUT}-bound timeout propagate as errors for the
     * caller's fail-soft policy to absorb.
     */
    public Mono<Outcome> deliver(Delivery delivery) {
        return serviceToken.get()
                .flatMap(token -> http.post()
                        .uri("/bff/user/internal/notifications")
                        .headers(h -> h.setBearerAuth(token))
                        .bodyValue(delivery)
                        .retrieve()
                        .bodyToMono(Outcome.class))
                .timeout(CALL_TIMEOUT);
    }
}
