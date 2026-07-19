package systems.hugmesh.actions.sync;

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
import systems.hugmesh.actions.casdoor.CasdoorClient;
import systems.hugmesh.actions.github.GitHubClient;
import systems.hugmesh.actions.github.GitHubTokenCache;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opt-in auto-grant of hugmesh.systems Casdoor admin to GitHub-authenticated
 * users who have admin authority on any repo (or org-level admin membership)
 * within the configured HugMesh GitHub orgs.
 *
 * <p><b>Disabled by default.</b> Set {@code app.actions.gh-org-admin.enabled=true}
 * (env: {@code GH_ORG_ADMIN_ENABLED}) for the superadmin to opt in. When disabled,
 * {@link #evaluateAndGrant} returns 404 immediately and the scheduled reconciler
 * is a no-op — no GitHub calls, no Casdoor writes. Superadmin can toggle this
 * live by updating the env var and rolling the pod.
 *
 * <p>When enabled, two callers:
 * <ol>
 *   <li>Sign-up: the registration remote forwards the user's GitHub access_token;
 *       this action checks org admin reach and grants the systems-admin role.</li>
 *   <li>Reconciliation: a scheduled tick re-evaluates the rule and revokes admin
 *       from any user whose GitHub reach has been removed.</li>
 * </ol>
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code app.actions.gh-org-admin.enabled} — master on/off switch (default false).</li>
 *   <li>{@code app.actions.gh-org-admin.orgs} — GitHub orgs whose admins map to
 *       systems-admin. Default: {@code Snapdragon-Systems,Snapdragon,HugMesh} (transition window - drop the old two once the GH org rename to HugMesh is confirmed live).</li>
 *   <li>{@code app.actions.gh-org-admin.systems-admin-role} — target Casdoor role.
 *       Default: {@code admin}.</li>
 * </ul>
 *
 * <p>Circuit breaker wraps the GitHub-side calls — a GitHub outage fails the
 * action without piling up retries.
 */
@Service
public class GitHubOrgAdminGrantAction {

    private static final Logger log = LoggerFactory.getLogger(GitHubOrgAdminGrantAction.class);

    private final GitHubClient github;
    private final CasdoorClient systemsCasdoor;
    private final GitHubTokenCache tokenCache;
    private final Set<String> hugmeshOrgs;
    private final String systemsAdminRole;
    private final boolean enabled;

    private final Counter granted;
    private final Counter revoked;
    private final Counter denied;
    private final Counter errors;

    public GitHubOrgAdminGrantAction(
            GitHubClient github,
            @Qualifier("systemsCasdoorClient") CasdoorClient systemsCasdoor,
            GitHubTokenCache tokenCache,
            @Value("${app.actions.gh-org-admin.orgs:Snapdragon-Systems,Snapdragon,HugMesh}") String orgsCsv,
            @Value("${app.actions.gh-org-admin.systems-admin-role:admin}") String systemsAdminRole,
            @Value("${app.actions.gh-org-admin.enabled:false}") boolean enabled,
            MeterRegistry registry) {
        this.github = github;
        this.systemsCasdoor = systemsCasdoor;
        this.tokenCache = tokenCache;
        this.hugmeshOrgs = parseCsv(orgsCsv);
        this.systemsAdminRole = systemsAdminRole;
        this.enabled = enabled;
        this.granted = Counter.builder("actions.gh_org_admin.granted")
                .description("Users granted hugmesh.systems admin via GitHub org membership")
                .register(registry);
        this.revoked = Counter.builder("actions.gh_org_admin.revoked")
                .description("Users stripped of hugmesh.systems admin after losing GitHub org admin")
                .register(registry);
        this.denied = Counter.builder("actions.gh_org_admin.denied")
                .description("Sign-up requests rejected because the user has no HugMesh org admin")
                .register(registry);
        this.errors = Counter.builder("actions.gh_org_admin.errors")
                .description("Sign-up evaluations that failed before reaching a decision")
                .register(registry);
    }

    /** Whether the GitHub org admin auto-grant feature is enabled on this instance. */
    public boolean isEnabled() { return enabled; }

    /**
     * Evaluate a single user against the org-admin rule on demand.
     * Called by the registration controller as soon as the
     * registration remote has the user's GitHub access_token. Returns
     * the resolved Casdoor member identifier on grant, or a 403
     * {@link ResponseStatusException} when the user has no
     * HugMesh admin reach. Returns 404 when the feature is disabled
     * ({@code app.actions.gh-org-admin.enabled=false}).
     */
    @CircuitBreaker(name = "github")
    public Mono<String> evaluateAndGrant(String githubBearer, String hugmeshUsername) {
        if (!enabled) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "GitHub org admin auto-grant is not enabled on this instance"));
        }
        // Remember this user's token so the scheduled revoke reconciler
        // can re-ask GitHub on their behalf later — GitHub admin reach is
        // only visible through the user's own token.
        tokenCache.put(hugmeshUsername, githubBearer);
        return github.hugmeshAdminOrgs(githubBearer, hugmeshOrgs)
            // Decide grant vs deny from the user's reachable HugMesh orgs.
            .flatMap(orgs -> grantIfReachable(orgs, hugmeshUsername))
            // Count only genuine evaluation failures — a deny (403) is an
            // expected outcome, not an error.
            .doOnError(this::countUnexpectedError);
    }

    /**
     * Grant branch of {@link #evaluateAndGrant}: deny with 403 when the
     * user has no reachable HugMesh org, otherwise add them to the
     * systems-admin role (idempotent) and resolve their member id.
     */
    private Mono<String> grantIfReachable(Set<String> reachableOrgs, String hugmeshUsername) {
        if (reachableOrgs.isEmpty()) {
            return denyNoReach(hugmeshUsername);
        }
        String member = memberIdFor(hugmeshUsername);
        return systemsCasdoor.getRoleMembers(systemsAdminRole)
            // Read-modify-write the role's member list: add this member unless
            // they are already present.
            .flatMap(current -> addMemberIfAbsent(current, member, hugmeshUsername));
    }

    /** Reject a sign-up with 403 and bump the denied counter — the user
     *  holds no admin reach on any configured HugMesh org. */
    private Mono<String> denyNoReach(String hugmeshUsername) {
        denied.increment();
        log.info("[gh-org-admin] denied: user={} reachable-orgs=[]", hugmeshUsername);
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "GitHub admin authority on a HugMesh org is required"));
    }

    /** Add {@code member} to the current systems-admin list and persist,
     *  or short-circuit (no write) when they are already a member.
     *  Resolves the member id in both cases. */
    private Mono<String> addMemberIfAbsent(List<String> current, String member, String hugmeshUsername) {
        if (current.contains(member)) {
            log.debug("[gh-org-admin] already-admin: user={}", hugmeshUsername);
            return Mono.just(member);
        }
        java.util.ArrayList<String> next = new java.util.ArrayList<>(current);
        next.add(member);
        return systemsCasdoor.setRoleMembers(systemsAdminRole, next)
            // Bump the granted counter only on a confirmed Casdoor write.
            .doOnNext(this::countIfGranted)
            .thenReturn(member);
    }

    /** Increment the granted counter when Casdoor confirmed the write. */
    private void countIfGranted(Boolean ok) {
        if (Boolean.TRUE.equals(ok)) granted.increment();
    }

    /** Count a failed evaluation — but not an expected deny
     *  ({@link ResponseStatusException}), which is a normal outcome. */
    private void countUnexpectedError(Throwable e) {
        if (!(e instanceof ResponseStatusException)) {
            errors.increment();
            log.warn("[gh-org-admin] evaluation failed: {}", e.getMessage());
        }
    }

    /**
     * Reconciliation hook — re-evaluates the rule for an existing
     * admin and revokes if their GitHub reach has fallen below the
     * threshold. The caller passes the GitHub bearer that's been
     * cached (or freshly minted via the OAuth client's refresh-token
     * flow).
     *
     * <p>This is the old on-demand, single-user revoke path. No
     * production caller invokes it any more — {@link
     * systems.hugmesh.actions.web.RegistrationController} only
     * calls {@link #evaluateAndGrant}, and the scheduled reconciler
     * ({@link GitHubOrgAdminReconcileAction}) does NOT fan this out
     * per-user — concurrent per-user read-modify-writes against the
     * one role list lose updates (last-writer-wins). It instead uses
     * {@link #isStillEligible} (read-only, safe to run concurrently)
     * to collect the losers and {@link #revokeAll} to apply them in a
     * single atomic-per-tick read-modify-write. Kept only for its unit
     * test coverage; prefer the {@code isStillEligible}/{@code
     * revokeAll} pair for any new revoke path.
     */
    @CircuitBreaker(name = "github")
    public Mono<Boolean> revokeIfNoLongerEligible(String githubBearer, String hugmeshUsername) {
        return isStillEligible(githubBearer)
            // Still reachable → nothing to do; otherwise prune them from the role.
            .flatMap(eligible -> Boolean.TRUE.equals(eligible)
                    ? Mono.just(false)
                    : revokeMember(hugmeshUsername));
    }

    /**
     * Revoke branch of {@link #revokeIfNoLongerEligible}: read the
     * current systems-admin list and remove this user when present.
     */
    private Mono<Boolean> revokeMember(String hugmeshUsername) {
        String member = memberIdFor(hugmeshUsername);
        return systemsCasdoor.getRoleMembers(systemsAdminRole)
            // Read-modify-write: drop this member from the list when present.
            .flatMap(current -> removeMemberIfPresent(current, member, hugmeshUsername));
    }

    /** Remove {@code member} from the current systems-admin list and
     *  persist, or short-circuit (no write, {@code false}) when they are
     *  not a member. On a confirmed write, count the revoke and evict the
     *  cached token. */
    private Mono<Boolean> removeMemberIfPresent(List<String> current, String member, String hugmeshUsername) {
        if (!current.contains(member)) return Mono.just(false);
        java.util.ArrayList<String> next = new java.util.ArrayList<>(current);
        next.remove(member);
        return systemsCasdoor.setRoleMembers(systemsAdminRole, next)
            // On a confirmed Casdoor write, count the revoke and stop
            // replaying this user's GitHub token.
            .doOnNext(ok -> countAndEvictIfRevoked(ok, hugmeshUsername))
            .thenReturn(true);
    }

    /** On a confirmed revoke write, bump the revoked counter and evict
     *  the user's cached GitHub token — they are no longer an admin, so
     *  there is no reason to keep replaying it. */
    private void countAndEvictIfRevoked(Boolean ok, String hugmeshUsername) {
        if (Boolean.TRUE.equals(ok)) {
            revoked.increment();
            tokenCache.evict(hugmeshUsername);
        }
    }

    /**
     * READ-ONLY eligibility probe for a single user — replays their
     * GitHub token and reports whether they still hold any HugMesh
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
        return github.hugmeshAdminOrgs(githubBearer, hugmeshOrgs)
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
            membersToRevoke.add(memberIdFor(username));
        }
        return systemsCasdoor.getRoleMembers(systemsAdminRole)
            // Single serialized read-modify-write for the whole tick: drop
            // every member we were asked to revoke, then persist once.
            .flatMap(current -> pruneMembers(current, membersToRevoke, usernamesToRevoke));
    }

    /** Remove {@code membersToRevoke} from {@code current} and persist the
     *  result. Prune-only: when nothing actually changed, writes nothing
     *  and returns {@code false}. */
    private Mono<Boolean> pruneMembers(List<String> current, Set<String> membersToRevoke,
                                       Set<String> usernamesToRevoke) {
        java.util.ArrayList<String> next = new java.util.ArrayList<>(current);
        boolean changed = next.removeAll(membersToRevoke);
        if (!changed) {
            // Nobody we were asked to revoke is actually still a
            // member — no-op tick, write nothing.
            return Mono.just(false);
        }
        return systemsCasdoor.setRoleMembers(systemsAdminRole, next)
            // On a confirmed write, count + evict tokens for the members
            // that were actually present before the prune.
            .doOnNext(ok -> evictRevokedTokens(ok, current, usernamesToRevoke));
    }

    /** On a confirmed batch-revoke write, bump the revoked counter and
     *  evict the cached token for each requested user that was actually a
     *  member before the prune. */
    private void evictRevokedTokens(Boolean ok, List<String> previousMembers, Set<String> usernamesToRevoke) {
        if (!Boolean.TRUE.equals(ok)) return;
        for (String username : usernamesToRevoke) {
            String member = memberIdFor(username);
            if (previousMembers.contains(member)) {
                revoked.increment();
                tokenCache.evict(username);
            }
        }
    }

    /** Casdoor member identifier ({@code <org>/<username>}) for a
     *  hugmesh.systems username — the shape Casdoor stores role
     *  members in. */
    public String memberIdFor(String hugmeshUsername) {
        return systemsCasdoor.organization() + "/" + hugmeshUsername;
    }

    /** The Casdoor role this action grants/revokes — the reconciler
     *  reads its current membership to decide who to re-evaluate. */
    public String systemsAdminRole() { return systemsAdminRole; }

    /** The Casdoor client used to read current admin membership during
     *  reconciliation. */
    public CasdoorClient systemsCasdoor() { return systemsCasdoor; }

    Set<String> hugmeshOrgsForTest() { return hugmeshOrgs; }

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
