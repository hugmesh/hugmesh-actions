package systems.snapdragon.actions.sync;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.casdoor.CasdoorClient;
import systems.snapdragon.actions.github.GitHubClient;
import systems.snapdragon.actions.github.GitHubTokenCache;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto-grants snapdragon.systems Casdoor admin to GitHub-authenticated
 * users who have admin authority on any repo (or org-level admin
 * membership) within the configured Snapdragon GitHub orgs.
 *
 * <p>Two callers:
 *
 * <ol>
 *   <li>Sign-up: the snapdragon.games registration remote forwards
 *       the user's GitHub access_token after the external-OAuth
 *       handshake completes. The action then evaluates the user's
 *       admin reach on GitHub and adds them to the admin role on
 *       snapdragon.systems Casdoor if the rule matches.</li>
 *   <li>Reconciliation: scheduled tick (every
 *       {@code app.actions.gh-org-admin.interval-ms}) walks the
 *       set of GitHub-linked Casdoor users and re-evaluates the
 *       rule. Drops admin from any user whose GitHub admin reach
 *       has been revoked since the last tick.</li>
 * </ol>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code app.actions.gh-org-admin.orgs} — the Snapdragon GitHub
 *       orgs whose admins should map to systems-admin. Defaults to
 *       {@code Snapdragon-Systems,Snapdragon}.</li>
 *   <li>{@code app.actions.gh-org-admin.systems-admin-role} — Casdoor
 *       role on the snapdragon.systems organization that signifies
 *       platform admin. Default: {@code admin}.</li>
 * </ul>
 *
 * <p>Circuit breaker wraps the GitHub-side calls — a GitHub outage
 * fails the action without piling up retries.
 */
@Service
public class GitHubOrgAdminGrantAction {

    private static final Logger log = LoggerFactory.getLogger(GitHubOrgAdminGrantAction.class);

    private final GitHubClient github;
    private final CasdoorClient systemsCasdoor;
    private final GitHubTokenCache tokenCache;
    private final Set<String> snapdragonOrgs;
    private final String systemsAdminRole;

    private final Counter granted;
    private final Counter revoked;
    private final Counter denied;
    private final Counter errors;

    public GitHubOrgAdminGrantAction(
            GitHubClient github,
            @Qualifier("systemsCasdoorClient") CasdoorClient systemsCasdoor,
            GitHubTokenCache tokenCache,
            @Value("${app.actions.gh-org-admin.orgs:Snapdragon-Systems,Snapdragon}") String orgsCsv,
            @Value("${app.actions.gh-org-admin.systems-admin-role:admin}") String systemsAdminRole,
            MeterRegistry registry) {
        this.github = github;
        this.systemsCasdoor = systemsCasdoor;
        this.tokenCache = tokenCache;
        this.snapdragonOrgs = parseCsv(orgsCsv);
        this.systemsAdminRole = systemsAdminRole;
        this.granted = Counter.builder("actions.gh_org_admin.granted")
                .description("Users granted snapdragon.systems admin via GitHub org membership")
                .register(registry);
        this.revoked = Counter.builder("actions.gh_org_admin.revoked")
                .description("Users stripped of snapdragon.systems admin after losing GitHub org admin")
                .register(registry);
        this.denied = Counter.builder("actions.gh_org_admin.denied")
                .description("Sign-up requests rejected because the user has no Snapdragon org admin")
                .register(registry);
        this.errors = Counter.builder("actions.gh_org_admin.errors")
                .description("Sign-up evaluations that failed before reaching a decision")
                .register(registry);
    }

    /**
     * Evaluate a single user against the org-admin rule on demand.
     * Called by the registration controller as soon as the
     * registration remote has the user's GitHub access_token. Returns
     * the resolved Casdoor member identifier on grant, or a 403
     * {@link ResponseStatusException} when the user has no
     * Snapdragon admin reach.
     */
    @CircuitBreaker(name = "github")
    public Mono<String> evaluateAndGrant(String githubBearer, String snapdragonUsername) {
        // Remember this user's token so the scheduled revoke reconciler
        // can re-ask GitHub on their behalf later — GitHub admin reach is
        // only visible through the user's own token.
        tokenCache.put(snapdragonUsername, githubBearer);
        return github.snapdragonAdminOrgs(githubBearer, snapdragonOrgs)
            .flatMap(orgs -> {
                if (orgs.isEmpty()) {
                    denied.increment();
                    log.info("[gh-org-admin] denied: user={} reachable-orgs=[]", snapdragonUsername);
                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "GitHub admin authority on a Snapdragon org is required"));
                }
                String member = systemsCasdoor.organization() + "/" + snapdragonUsername;
                return systemsCasdoor.getRoleMembers(systemsAdminRole)
                    .flatMap(current -> {
                        if (current.contains(member)) {
                            log.debug("[gh-org-admin] already-admin: user={}", snapdragonUsername);
                            return Mono.just(member);
                        }
                        java.util.ArrayList<String> next = new java.util.ArrayList<>(current);
                        next.add(member);
                        return systemsCasdoor.setRoleMembers(systemsAdminRole, next)
                            .doOnNext(ok -> {
                                if (Boolean.TRUE.equals(ok)) granted.increment();
                            })
                            .thenReturn(member);
                    });
            })
            .doOnError(e -> {
                if (!(e instanceof ResponseStatusException)) {
                    errors.increment();
                    log.warn("[gh-org-admin] evaluation failed: {}", e.getMessage());
                }
            });
    }

    /**
     * Reconciliation hook — re-evaluates the rule for an existing
     * admin and revokes if their GitHub reach has fallen below the
     * threshold. The caller passes the GitHub bearer that's been
     * cached (or freshly minted via the OAuth client's refresh-token
     * flow).
     *
     * <p>This is the on-demand, single-user revoke path kept intact
     * for the grant/sign-in flow. The scheduled reconciler does NOT
     * fan this out per-user — concurrent per-user read-modify-writes
     * against the one role list lost updates (last-writer-wins). It
     * instead uses {@link #isStillEligible} (read-only, safe to run
     * concurrently) to collect the losers and {@link #revokeAll} to
     * apply them in a single atomic-per-tick read-modify-write.
     */
    @CircuitBreaker(name = "github")
    public Mono<Boolean> revokeIfNoLongerEligible(String githubBearer, String snapdragonUsername) {
        return isStillEligible(githubBearer)
            .flatMap(eligible -> {
                if (Boolean.TRUE.equals(eligible)) return Mono.just(false);
                String member = systemsCasdoor.organization() + "/" + snapdragonUsername;
                return systemsCasdoor.getRoleMembers(systemsAdminRole)
                    .flatMap(current -> {
                        if (!current.contains(member)) return Mono.just(false);
                        java.util.ArrayList<String> next = new java.util.ArrayList<>(current);
                        next.remove(member);
                        return systemsCasdoor.setRoleMembers(systemsAdminRole, next)
                            .doOnNext(ok -> {
                                if (Boolean.TRUE.equals(ok)) {
                                    revoked.increment();
                                    // No longer an admin → no reason to keep
                                    // replaying their GitHub token.
                                    tokenCache.evict(snapdragonUsername);
                                }
                            })
                            .thenReturn(true);
                    });
            });
    }

    /**
     * READ-ONLY eligibility probe for a single user — replays their
     * GitHub token and reports whether they still hold any Snapdragon
     * admin reach. Performs NO Casdoor write, so it is safe for the
     * reconciler to run many of these concurrently.
     *
     * <p>The Mono ERRORS (rather than resolving false) when GitHub
     * reach can't be determined — e.g. a transient 5xx / rate-limit
     * surfaced by {@link GitHubClient#isOrgAdmin}. The reconciler must
     * treat that error as inconclusive evidence and SKIP the user this
     * tick, never revoke. Only a clean {@code Set.of()} result means
     * "genuinely lost all reach".
     */
    @CircuitBreaker(name = "github")
    public Mono<Boolean> isStillEligible(String githubBearer) {
        return github.snapdragonAdminOrgs(githubBearer, snapdragonOrgs)
            .map(orgs -> !orgs.isEmpty());
    }

    /**
     * Batch revoke for the reconcile tick — removes every member in
     * {@code usernamesToRevoke} from the systems-admin role in a SINGLE
     * read-modify-write: one {@code getRoleMembers} → {@code removeAll}
     * → one {@code setRoleMembers}. This is the fix for the lost-update
     * race: the reconciler evaluates eligibility concurrently but funnels
     * the actual mutation through this one serialized write per tick, so
     * two admins losing reach in the same tick can never overwrite each
     * other's removal.
     *
     * <p>Idempotent and prune-only: an empty input, or a set whose
     * members are all already absent, writes NOTHING (returns false).
     * Evicts the cached token of every member actually removed.
     */
    @CircuitBreaker(name = "github")
    public Mono<Boolean> revokeAll(Set<String> usernamesToRevoke) {
        if (usernamesToRevoke == null || usernamesToRevoke.isEmpty()) {
            return Mono.just(false);
        }
        Set<String> membersToRevoke = new HashSet<>();
        for (String username : usernamesToRevoke) {
            membersToRevoke.add(systemsCasdoor.organization() + "/" + username);
        }
        return systemsCasdoor.getRoleMembers(systemsAdminRole)
            .flatMap(current -> {
                java.util.ArrayList<String> next = new java.util.ArrayList<>(current);
                boolean changed = next.removeAll(membersToRevoke);
                if (!changed) {
                    // Nobody we were asked to revoke is actually still a
                    // member — no-op tick, write nothing.
                    return Mono.just(false);
                }
                return systemsCasdoor.setRoleMembers(systemsAdminRole, next)
                    .doOnNext(ok -> {
                        if (Boolean.TRUE.equals(ok)) {
                            for (String username : usernamesToRevoke) {
                                String member = systemsCasdoor.organization() + "/" + username;
                                if (current.contains(member)) {
                                    revoked.increment();
                                    tokenCache.evict(username);
                                }
                            }
                        }
                    });
            });
    }

    /** Casdoor member identifier ({@code <org>/<username>}) for a
     *  snapdragon.systems username — the shape Casdoor stores role
     *  members in. */
    public String memberIdFor(String snapdragonUsername) {
        return systemsCasdoor.organization() + "/" + snapdragonUsername;
    }

    /** The Casdoor role this action grants/revokes — the reconciler
     *  reads its current membership to decide who to re-evaluate. */
    public String systemsAdminRole() { return systemsAdminRole; }

    /** The Casdoor client used to read current admin membership during
     *  reconciliation. */
    public CasdoorClient systemsCasdoor() { return systemsCasdoor; }

    Set<String> snapdragonOrgsForTest() { return snapdragonOrgs; }

    static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String s : List.of(csv.split(","))) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return Set.copyOf(out);
    }
}
