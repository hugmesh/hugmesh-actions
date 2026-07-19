package systems.hugmesh.actions.sync;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import systems.hugmesh.actions.casdoor.CasdoorClient;
import systems.hugmesh.actions.casdoor.CasdoorClient.NewUser;
import systems.hugmesh.actions.oidc.OidcRoleHookClient;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OIDC default-role registration hook — a DEFAULT-OFF scheduled probe that
 * observes newly-created Casdoor users and applies each user's
 * OIDC-provider default roles by calling the EXISTING admin-bff ROLE_SERVICE
 * endpoint {@code POST /bff/internal/oidc/first-login}.
 *
 * <p>This is the missing <em>caller</em> for the registration-flow TODO in
 * admin-bff ({@code OidcProviderRoleRepository}): admin-bff already reads the
 * per-(domain,provider) default-role map and applies it via Casdoor; it just
 * needed something to invoke it once per new signup. Casdoor exposes no
 * first-login webhook in this org (every existing role propagation here is
 * scheduled-reconcile — see {@link GameMasterAdminSyncAction} and
 * {@link GitHubOrgAdminReconcileAction}), and hugmesh-actions has no
 * datasource of its own (it is a pure webflux effector), so the design is a
 * scheduled diff against Casdoor's newest users rather than a push.
 *
 * <p><b>Triple-inert by default:</b>
 * <ol>
 *   <li>{@code app.actions.oidc-default-roles.enabled=false} (default) →
 *       {@link #runTick()} returns immediately (no Casdoor read, no admin-bff
 *       call). Same guard shape as {@code GitHubOrgAdminGrantAction.isEnabled}.</li>
 *   <li>{@link OidcRoleHookClient}'s config is
 *       {@code @ConditionalOnProperty(enabled=true)} so the client bean does not
 *       even exist when off; this action injects it via {@link ObjectProvider}
 *       and short-circuits if absent — defence in depth.</li>
 *   <li>Even fully enabled, when NO default-role map is configured for a
 *       provider+domain (the default platform state) the admin-bff endpoint
 *       returns {@code assignedRoles:[]} and performs ZERO Casdoor writes.</li>
 * </ol>
 *
 * <p>Tick (every {@code app.actions.oidc-default-roles.interval-ms}, default
 * 5 min):
 * <ol>
 *   <li>Read the {@code limit} newest Casdoor users (sorted by created_time).</li>
 *   <li>Keep only users created within the {@code lookback} window AND whose
 *       {@code signupApplication} maps to a configured provider AND not already
 *       in the process-local seen-set.</li>
 *   <li>For each, resolve {@code (domain, provider, username)} and call the
 *       admin-bff first-login endpoint, then mark the user seen.</li>
 * </ol>
 *
 * <p>Idempotency is double-guaranteed: the seen-set prevents re-POSTing within
 * a pod lifetime (mirrors {@code GitHubTokenCache}'s process-local best-effort
 * {@link ConcurrentHashMap} pattern), and {@code CasdoorAdminClient.addUserToRole}
 * is itself idempotent (skips existing members), so a pod restart re-probing a
 * user within the lookback window is harmless. The seen-set is bounded by
 * pruning entries older than the lookback on each tick.
 *
 * <p>Metrics: {@code actions.oidc_default_roles.{applied,skipped,errors}}.
 */
@Service
public class OidcDefaultRoleProbeAction {

    private static final Logger log = LoggerFactory.getLogger(OidcDefaultRoleProbeAction.class);

    private final CasdoorClient systemsCasdoor;
    private final ObjectProvider<OidcRoleHookClient> roleHookProvider;
    private final boolean enabled;
    private final String domain;
    private final int limit;
    private final Duration lookback;
    private final Map<String, String> providerMap;

    private final Counter applied;
    private final Counter skipped;
    private final Counter errors;

    /** Process-local best-effort de-dup: username -> instant first seen.
     *  Prevents re-POSTing the same new user within a pod lifetime; pruned
     *  to the lookback window each tick so it can't grow unbounded. */
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    private final AtomicReference<Instant> lastSuccessAt =
            new AtomicReference<>(Instant.EPOCH);

    public OidcDefaultRoleProbeAction(
            @Qualifier("systemsCasdoorClient") CasdoorClient systemsCasdoor,
            ObjectProvider<OidcRoleHookClient> roleHookProvider,
            @Value("${app.actions.oidc-default-roles.enabled:false}") boolean enabled,
            @Value("${app.actions.oidc-default-roles.domain:default}") String domain,
            @Value("${app.actions.oidc-default-roles.limit:50}") int limit,
            @Value("${app.actions.oidc-default-roles.lookback:PT10M}") Duration lookback,
            @Value("${app.actions.oidc-default-roles.provider-map:}") String providerMapCsv,
            MeterRegistry registry) {
        this.systemsCasdoor = systemsCasdoor;
        this.roleHookProvider = roleHookProvider;
        this.enabled = enabled;
        this.domain = domain;
        this.limit = limit > 0 ? limit : 50;
        this.lookback = lookback == null || lookback.isZero() || lookback.isNegative()
                ? Duration.ofMinutes(10) : lookback;
        this.providerMap = parseProviderMap(providerMapCsv);
        this.applied = Counter.builder("actions.oidc_default_roles.applied")
                .description("New users for whom OIDC-provider default roles were applied via admin-bff")
                .register(registry);
        this.skipped = Counter.builder("actions.oidc_default_roles.skipped")
                .description("New users skipped (already seen / unmapped provider / outside lookback)")
                .register(registry);
        this.errors = Counter.builder("actions.oidc_default_roles.errors")
                .description("Probe ticks or per-user applies that failed")
                .register(registry);
    }

    /** Whether the OIDC default-role registration hook is enabled on this instance. */
    public boolean isEnabled() { return enabled; }

    /** Test hook — last successful probe tick timestamp. */
    public Instant lastSuccessAt() { return lastSuccessAt.get(); }

    Map<String, String> providerMapForTest() { return providerMap; }

    @PostConstruct
    void start() {
        if (enabled) {
            log.info("[oidc-role-hook] configured: domain={} limit={} lookback={} providerMap={}",
                    domain, limit, lookback, providerMap.keySet());
        } else {
            log.info("[oidc-role-hook] DISABLED (app.actions.oidc-default-roles.enabled=false) "
                    + "— no Casdoor read, no admin-bff call");
        }
    }

    @Scheduled(
        fixedDelayString = "${app.actions.oidc-default-roles.interval-ms:300000}",
        initialDelayString = "${app.actions.oidc-default-roles.initial-delay-ms:15000}")
    public void runTick() {
        if (!enabled) {
            return; // INERT — feature off.
        }
        OidcRoleHookClient hook = roleHookProvider.getIfAvailable();
        if (hook == null) {
            // Defence in depth: the @ConditionalOnProperty client bean is absent.
            log.debug("[oidc-role-hook] enabled but role-hook client bean absent — skipping tick");
            return;
        }
        log.debug("[oidc-role-hook] tick start");
        runOnce(hook)
            .doOnSuccess(v -> lastSuccessAt.set(Instant.now()))
            .doOnError(e -> {
                errors.increment();
                log.warn("[oidc-role-hook] tick failed: {}", e.getMessage());
            })
            .onErrorResume(e -> Mono.empty())
            .subscribe();
    }

    @CircuitBreaker(name = "casdoor")
    Mono<Void> runOnce(OidcRoleHookClient hook) {
        Instant now = Instant.now();
        pruneSeen(now);
        return systemsCasdoor.listNewUsers(limit)
            .flatMap(users -> Flux.fromIterable(users)
                .flatMap(user -> handleUser(hook, user, now), 1)
                .then());
    }

    /**
     * Decide + act on a single newly-listed user. Returns a Mono that never
     * errors out of the tick: a per-user apply failure increments the errors
     * counter and resolves empty so the remaining users are still processed.
     */
    Mono<Void> handleUser(OidcRoleHookClient hook, NewUser user, Instant now) {
        String username = user.name();
        if (username == null || username.isBlank()) {
            skipped.increment();
            return Mono.empty();
        }
        // Outside the lookback window → skip (older signup, already handled
        // on a prior tick or before this pod started).
        if (!withinLookback(user.createdTime(), now)) {
            skipped.increment();
            return Mono.empty();
        }
        // Unmapped signup application → skip (never mis-assign a provider).
        String provider = providerMap.get(user.signupApplication());
        if (provider == null) {
            skipped.increment();
            log.debug("[oidc-role-hook] skip user={} unmapped signupApplication={}",
                    username, user.signupApplication());
            return Mono.empty();
        }
        // Already handled this pod lifetime → skip (idempotency layer 1).
        if (seen.putIfAbsent(username, now) != null) {
            skipped.increment();
            return Mono.empty();
        }
        return hook.applyDefaultRoles(domain, provider, username)
            .doOnNext(resp -> {
                applied.increment();
                int n = resp != null && resp.assignedRoles() != null ? resp.assignedRoles().size() : 0;
                log.info("[oidc-role-hook] applied first-login domain={} provider={} user={} roles={}",
                        domain, provider, username, n);
            })
            .onErrorResume(e -> {
                // The hook client already fails OPEN to a no-op, so reaching
                // here is unexpected; never let it abort the tick. Un-mark so
                // a later tick can retry within the lookback window.
                errors.increment();
                seen.remove(username, now);
                log.warn("[oidc-role-hook] apply failed user={}: {}", username, e.toString());
                return Mono.empty();
            })
            .then();
    }

    private boolean withinLookback(String createdTime, Instant now) {
        Instant created = parseInstant(createdTime);
        if (created == null) {
            // No / unparseable timestamp: treat as in-window so a genuinely-new
            // user is not silently dropped on a Casdoor format quirk; the
            // seen-set + admin-bff idempotency still bound the effect.
            return true;
        }
        return !created.isBefore(now.minus(lookback));
    }

    private void pruneSeen(Instant now) {
        Instant cutoff = now.minus(lookback);
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    /** Best-effort parse of Casdoor's created_time string. Casdoor emits an
     *  RFC-3339 / ISO-8601 instant (e.g. {@code 2026-06-19T10:15:30Z}); a
     *  value we can't parse returns null (treated as in-window). */
    static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /**
     * Parse the {@code provider-map} CSV — entries like
     * {@code app-google=google,app-github=github} mapping a Casdoor
     * signupApplication to an OIDC provider key. Blank / malformed entries
     * are dropped. An empty map means NO user is ever mapped → the probe is
     * effectively inert even when enabled.
     */
    static Map<String, String> parseProviderMap(String csv) {
        Map<String, String> out = new HashMap<>();
        if (csv == null || csv.isBlank()) return Map.copyOf(out);
        for (String pair : List.of(csv.split(","))) {
            String t = pair.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq <= 0 || eq == t.length() - 1) continue;
            String key = t.substring(0, eq).trim();
            String val = t.substring(eq + 1).trim();
            if (!key.isEmpty() && !val.isEmpty()) {
                out.put(key, val);
            }
        }
        return Map.copyOf(out);
    }

    Set<String> seenSnapshot() { return Set.copyOf(seen.keySet()); }
}
