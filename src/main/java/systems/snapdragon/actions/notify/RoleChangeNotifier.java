package systems.snapdragon.actions.notify;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.casdoor.CasdoorClient;

import java.time.Instant;

/**
 * The reconcilers' notification writer — turns a role grant/revoke a
 * reconciler just APPLIED into one in-app notification for the affected user,
 * delivered through user-bff's SERVICE-only internal endpoint
 * ({@link UserNotificationClient}). This is the platform's "first caller" of
 * the notifications v1 floor: until it runs live, nothing writes
 * {@code user_notifications} in normal operation.
 *
 * <p><b>Dark by default.</b> The {@link UserNotificationClient} bean only
 * exists when {@code app.actions.notify.enabled=true} (see
 * {@link UserNotificationClientConfig}); when it is absent every call here is
 * an explicit, logged no-op ({@code actions.notify.skipped} counts it) — the
 * reconcilers' behaviour is bit-for-bit unchanged. NEVER fakes success:
 * the skip is visible in logs + metrics, not silently swallowed.
 *
 * <p><b>Subject resolution.</b> user-bff keys notifications by JWT subject
 * (the Casdoor user UUID), but reconcilers speak {@code <org>/<username>}
 * member ids — so the notifier resolves the username against the
 * snapdragon.systems Casdoor ({@link CasdoorClient#getUserId}; systems is the
 * platform's login identity org, whatever downstream org the role lives in).
 * An unresolvable user is SKIPPED (counted), never guessed.
 *
 * <p><b>Fail-soft, notify-after-apply.</b> Reconcilers call this AFTER the
 * role write is confirmed, and any delivery failure resolves EMPTY (counted
 * in {@code actions.notify.errors}) — a lost notification must never fail or
 * retry-loop a reconcile tick. Idempotency lives in user-bff: the
 * {@code (sourceTable, sourceId)} pair drives its deterministic row id, and
 * this notifier stamps the tick's epoch-millis into the sourceId so a retried
 * HTTP call dedupes while a genuinely new grant/revoke of the same role later
 * still notifies.
 */
@Component
public class RoleChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(RoleChangeNotifier.class);

    /** user-bff's fixed-vocabulary category for "your roles changed". */
    static final String CATEGORY_ROLE_CHANGE = "role-change";

    private final ObjectProvider<UserNotificationClient> client;
    private final CasdoorClient systemsCasdoor;
    private final String domain;

    private final Counter delivered;
    private final Counter deduped;
    private final Counter suppressed;
    private final Counter skipped;
    private final Counter errors;

    public RoleChangeNotifier(
            ObjectProvider<UserNotificationClient> client,
            @Qualifier("systemsCasdoorClient") CasdoorClient systemsCasdoor,
            @Value("${app.actions.notify.domain:default}") String domain,
            MeterRegistry registry) {
        this.client = client;
        this.systemsCasdoor = systemsCasdoor;
        this.domain = domain;
        this.delivered  = Counter.builder("actions.notify.delivered")
                .description("In-app notifications freshly delivered to user-bff")
                .register(registry);
        this.deduped    = Counter.builder("actions.notify.deduped")
                .description("Deliveries user-bff recognised as an already-delivered source event")
                .register(registry);
        this.suppressed = Counter.builder("actions.notify.suppressed")
                .description("Deliveries user-bff suppressed per the target's category preference")
                .register(registry);
        this.skipped    = Counter.builder("actions.notify.skipped")
                .description("Notifications skipped (writer dark, or target user unresolvable)")
                .register(registry);
        this.errors     = Counter.builder("actions.notify.errors")
                .description("Notification deliveries that failed (fail-soft — the reconcile tick continues)")
                .register(registry);
    }

    /**
     * Notify {@code member} (a Casdoor {@code <org>/<username>} id or bare
     * username) that {@code roleLabel} was granted/revoked. Best-effort:
     * ALWAYS completes empty — dark writer, unresolvable user and delivery
     * failure are counted + logged, never propagated. Call AFTER the role
     * write is confirmed.
     *
     * @param member      the affected user, as the reconciler knows them
     * @param roleLabel   human-readable role description for the title
     *                    (e.g. {@code "admin on snapdragon.games"})
     * @param granted     true = role granted, false = role revoked
     * @param sourceTable bounded token identifying the emitting reconciler
     *                    (e.g. {@code actions_gm_sync}) — drives user-bff's
     *                    idempotent row id together with the sourceId
     */
    public Mono<Void> roleChanged(String member, String roleLabel, boolean granted,
                                  String sourceTable) {
        UserNotificationClient c = client.getIfAvailable();
        if (c == null) {
            skipped.increment();
            log.debug("[notify] dark (app.actions.notify.enabled=false) — skipping {} '{}' for {}",
                    granted ? "grant" : "revoke", roleLabel, member);
            return Mono.empty();
        }
        String username = usernameOf(member);
        if (username == null) {
            skipped.increment();
            log.warn("[notify] unusable member id '{}' — skipping", member);
            return Mono.empty();
        }
        return systemsCasdoor.getUserId(username)
                .flatMap(subject -> deliver(c, subject, username, roleLabel, granted, sourceTable))
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    skipped.increment();
                    log.warn("[notify] could not resolve '{}' to a Casdoor subject — skipping", username);
                }))
                .onErrorResume(e -> {
                    errors.increment();
                    log.warn("[notify] delivery failed for {} ({} '{}'): {}",
                            username, granted ? "grant" : "revoke", roleLabel, e.toString());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<?> deliver(UserNotificationClient c, String subject, String username,
                            String roleLabel, boolean granted, String sourceTable) {
        String direction = granted ? "grant" : "revoke";
        String title = (granted ? "Role granted: " : "Role revoked: ") + roleLabel;
        String body = granted
                ? "The platform granted your account the role \"" + roleLabel
                        + "\" during automatic role reconciliation."
                : "The platform removed the role \"" + roleLabel
                        + "\" from your account during automatic role reconciliation.";
        // Epoch-millis in the sourceId: an HTTP retry of THIS delivery dedupes on
        // user-bff's deterministic id; a later re-grant/re-revoke of the same role
        // is a genuinely new event and still notifies.
        String sourceId = direction + ":" + username + ":" + Instant.now().toEpochMilli();
        return c.deliver(new UserNotificationClient.Delivery(
                        domain, subject, CATEGORY_ROLE_CHANGE, title, body, sourceTable, sourceId))
                .doOnNext(outcome -> count(outcome, username, direction, roleLabel));
    }

    /** Route an honest user-bff outcome to the matching counter. */
    private void count(UserNotificationClient.Outcome outcome, String username,
                       String direction, String roleLabel) {
        if (!outcome.delivered()) {
            suppressed.increment();
            log.debug("[notify] suppressed by {}'s category preference ({} '{}')",
                    username, direction, roleLabel);
        } else if (outcome.deduped()) {
            deduped.increment();
        } else {
            delivered.increment();
            log.info("[notify] delivered {} '{}' to {}", direction, roleLabel, username);
        }
    }

    /** Pull the username out of a Casdoor {@code <org>/<username>} member id
     *  (bare usernames pass through). Null for an unusable shape. */
    static String usernameOf(String member) {
        if (member == null) return null;
        int slash = member.indexOf('/');
        String username = slash < 0 ? member : member.substring(slash + 1);
        return username.isBlank() ? null : username;
    }
}
