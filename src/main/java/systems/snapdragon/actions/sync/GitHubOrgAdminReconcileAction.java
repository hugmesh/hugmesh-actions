package systems.snapdragon.actions.sync;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.casdoor.CasdoorClient;
import systems.snapdragon.actions.github.GitHubTokenCache;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduled reconciler that closes audit gap #5: an admin whose GitHub
 * org-admin reach has been removed must lose snapdragon.systems admin
 * automatically, not just at the next sign-in.
 *
 * <p>{@link GitHubOrgAdminGrantAction#revokeIfNoLongerEligible} already
 * encapsulates the revoke decision for a single user, but nothing called
 * it on a timer — this action is that caller. It mirrors the shape of
 * {@link GameMasterAdminSyncAction}: a reactive {@code Mono} pipeline,
 * a resilience4j {@code @CircuitBreaker} over the external side, and a
 * configurable {@code interval-ms} / {@code initial-delay-ms} under
 * {@code app.actions.gh-org-admin.reconcile.*}.
 *
 * <p>Tick (every {@code app.actions.gh-org-admin.reconcile.interval-ms},
 * default 6 h):
 *
 * <ol>
 *   <li>Read the current member list of the systems-admin role from
 *       snapdragon.systems Casdoor.</li>
 *   <li>For each member we hold a fresh GitHub token for (seeded by the
 *       registration flow into {@link GitHubTokenCache}), replay that
 *       token against GitHub and revoke systems-admin iff their reach
 *       has dropped to zero.</li>
 *   <li>Members we have NO fresh token for are left untouched — we have
 *       no evidence either way, so we never revoke on missing evidence
 *       (prune-only, idempotent). They get re-evaluated on their next
 *       sign-in via {@code evaluateAndGrant}.</li>
 * </ol>
 *
 * <p>Fully non-blocking — the per-user revoke checks run as a bounded
 * concurrent {@link Flux} and the whole tick is a single subscribed
 * {@code Mono<Void>}. Idempotent: a tick where everyone still has reach
 * is a pure read with zero writes.
 *
 * <p>The circuit breaker is the same {@code github} instance the grant
 * action uses; a GitHub outage opens it and the tick short-circuits,
 * retrying when it half-opens on the next tick.
 */
@Service
public class GitHubOrgAdminReconcileAction {

    private static final Logger log = LoggerFactory.getLogger(GitHubOrgAdminReconcileAction.class);

    /** Cap concurrent GitHub re-evaluations so a large admin set + the
     *  paginated repo walk inside each check don't fan out unbounded. */
    private static final int MAX_CONCURRENCY = 4;

    private final GitHubOrgAdminGrantAction grantAction;
    private final GitHubTokenCache tokenCache;

    private final Counter reconciled;
    private final Counter skipped;
    private final Counter errors;
    private final AtomicReference<Instant> lastSuccessAt =
            new AtomicReference<>(Instant.EPOCH);

    public GitHubOrgAdminReconcileAction(
            GitHubOrgAdminGrantAction grantAction,
            GitHubTokenCache tokenCache,
            MeterRegistry registry) {
        this.grantAction = grantAction;
        this.tokenCache = tokenCache;
        this.reconciled = Counter.builder("actions.gh_org_admin.reconciled")
                .description("Admins re-evaluated against GitHub during the revoke reconcile tick")
                .register(registry);
        this.skipped = Counter.builder("actions.gh_org_admin.reconcile_skipped")
                .description("Admins left untouched on a reconcile tick for lack of a fresh GitHub token")
                .register(registry);
        this.errors = Counter.builder("actions.gh_org_admin.reconcile_errors")
                .description("Reconcile ticks that failed to read Casdoor or evaluate GitHub")
                .register(registry);
    }

    @PostConstruct
    void start() {
        log.info("[gh-org-admin-reconcile] configured: role={} (prune-only, evidence-gated)",
                grantAction.systemsAdminRole());
    }

    /** Test hook — last successful reconcile timestamp for health
     *  checks / integration assertions. */
    public Instant lastSuccessAt() { return lastSuccessAt.get(); }

    @Scheduled(
        fixedDelayString = "${app.actions.gh-org-admin.reconcile.interval-ms:21600000}",
        initialDelayString = "${app.actions.gh-org-admin.reconcile.initial-delay-ms:30000}")
    public void runTick() {
        log.debug("[gh-org-admin-reconcile] tick start");
        runOnce()
            .doOnSuccess(v -> lastSuccessAt.set(Instant.now()))
            .doOnError(e -> {
                errors.increment();
                log.warn("[gh-org-admin-reconcile] tick failed: {}", e.getMessage());
            })
            .onErrorResume(e -> Mono.empty())
            .subscribe();
    }

    @CircuitBreaker(name = "github")
    Mono<Void> runOnce() {
        CasdoorClient casdoor = grantAction.systemsCasdoor();
        String role = grantAction.systemsAdminRole();
        return casdoor.getRoleMembers(role)
            .flatMap(members -> reconcile(role, members));
    }

    /**
     * Re-evaluate each current admin we have a fresh GitHub token for and
     * revoke the ones whose reach has dropped to zero. Members without a
     * fresh token are counted as skipped and left in place.
     *
     * <p>Two distinct phases to defeat the lost-update race (see class
     * javadoc): a CONCURRENT read-only eligibility scan that COLLECTS
     * the usernames that have genuinely lost all reach, followed by a
     * SINGLE serialized read-modify-write that removes them all from the
     * role at once ({@link GitHubOrgAdminGrantAction#revokeAll}). The
     * GitHub reads are side-effect-free so concurrency is safe there;
     * the one and only role mutation happens after the scan, so two
     * admins losing reach in the same tick can never clobber each
     * other's removal.
     */
    Mono<Void> reconcile(String role, List<String> members) {
        // Map current members back to the bare username keys the token
        // cache is keyed on, dropping any whose <org>/ prefix we can't
        // resolve (defensive — Casdoor always stores the prefixed form).
        Map<String, String> usernameByMember = new HashMap<>();
        for (String member : members) {
            String username = usernameOf(member);
            if (username != null) usernameByMember.put(member, username);
        }

        if (usernameByMember.isEmpty()) {
            log.debug("[gh-org-admin-reconcile] no resolvable admins ({} raw members)", members.size());
            return Mono.empty();
        }

        log.debug("[gh-org-admin-reconcile] evaluating {} admins (cached tokens: {})",
                usernameByMember.size(), tokenCache.size());

        // Phase 1 — CONCURRENT read-only eligibility scan. Each
        // evaluateOne emits the username iff it has genuinely lost all
        // reach (and we hold a fresh token); it emits nothing when the
        // user is skipped (no token), still eligible, or its GitHub
        // probe was inconclusive (transient error). No Casdoor writes
        // here, so MAX_CONCURRENCY parallelism is safe.
        return Flux.fromIterable(usernameByMember.values())
            .flatMap(this::evaluateOne, MAX_CONCURRENCY)
            .collect(java.util.stream.Collectors.toSet())
            // Phase 2 — SINGLE serialized read-modify-write for the
            // whole loser set. One getRoleMembers → removeAll →
            // setRoleMembers; a no-op tick (empty set) writes nothing.
            .flatMap(toRevoke -> {
                if (toRevoke.isEmpty()) return Mono.empty();
                log.info("[gh-org-admin-reconcile] revoking {} admin(s) — GitHub reach removed: {}",
                        toRevoke.size(), toRevoke);
                return grantAction.revokeAll(toRevoke).then();
            })
            .then();
    }

    /** READ-ONLY evaluation of a single admin. Emits the username iff it
     *  has genuinely lost all reach AND we hold a fresh token for it;
     *  emits nothing (so it is NOT collected for revoke) when:
     *  <ul>
     *    <li>we have no fresh token (skip — no evidence either way);</li>
     *    <li>the user is still eligible (leave untouched);</li>
     *    <li>the GitHub probe was inconclusive — a transient/non-404
     *        error — which we treat exactly like missing evidence and
     *        SKIP this tick rather than revoke.</li>
     *  </ul>
     *  Performs NO Casdoor write — the actual removal is batched into a
     *  single write back in {@link #reconcile}. */
    private Mono<String> evaluateOne(String username) {
        Optional<String> token = tokenCache.get(username);
        if (token.isEmpty()) {
            skipped.increment();
            log.debug("[gh-org-admin-reconcile] skip {} — no fresh GitHub token", username);
            return Mono.empty();
        }
        reconciled.increment();
        return grantAction.isStillEligible(token.get())
            .flatMap(eligible -> Boolean.TRUE.equals(eligible)
                    ? Mono.<String>empty()
                    : Mono.just(username))
            .onErrorResume(e -> {
                errors.increment();
                log.warn("[gh-org-admin-reconcile] evaluation inconclusive for {} — skipping: {}",
                        username, e.getMessage());
                // Transient / inconclusive GitHub error → treat as
                // missing evidence and SKIP. Emitting nothing keeps the
                // user OUT of the revoke set, so a transient error can
                // never cause a revoke.
                return Mono.empty();
            });
    }

    /** Pull the username out of a Casdoor {@code <org>/<username>} member
     *  id. Returns null for an unexpected shape so the caller skips it. */
    static String usernameOf(String member) {
        if (member == null) return null;
        int slash = member.indexOf('/');
        if (slash < 0) return member.isBlank() ? null : member;
        String username = member.substring(slash + 1);
        return username.isBlank() ? null : username;
    }
}
