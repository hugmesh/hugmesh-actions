package systems.snapdragon.actions.sync;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.casdoor.CasdoorClient;
import systems.snapdragon.actions.github.GitHubClient;

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
    private final Set<String> snapdragonOrgs;
    private final String systemsAdminRole;

    private final Counter granted;
    private final Counter revoked;
    private final Counter denied;
    private final Counter errors;

    public GitHubOrgAdminGrantAction(
            GitHubClient github,
            CasdoorClient systemsCasdoor,
            @Value("${app.actions.gh-org-admin.orgs:Snapdragon-Systems,Snapdragon}") String orgsCsv,
            @Value("${app.actions.gh-org-admin.systems-admin-role:admin}") String systemsAdminRole,
            MeterRegistry registry) {
        this.github = github;
        this.systemsCasdoor = systemsCasdoor;
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
     * flow). Designed to be called from a periodic task in a
     * follow-up; this skeleton encapsulates the revoke side without
     * requiring the cache layer to exist yet.
     */
    @CircuitBreaker(name = "github")
    public Mono<Boolean> revokeIfNoLongerEligible(String githubBearer, String snapdragonUsername) {
        return github.snapdragonAdminOrgs(githubBearer, snapdragonOrgs)
            .flatMap(orgs -> {
                if (!orgs.isEmpty()) return Mono.just(false);
                String member = systemsCasdoor.organization() + "/" + snapdragonUsername;
                return systemsCasdoor.getRoleMembers(systemsAdminRole)
                    .flatMap(current -> {
                        if (!current.contains(member)) return Mono.just(false);
                        java.util.ArrayList<String> next = new java.util.ArrayList<>(current);
                        next.remove(member);
                        return systemsCasdoor.setRoleMembers(systemsAdminRole, next)
                            .doOnNext(ok -> {
                                if (Boolean.TRUE.equals(ok)) revoked.increment();
                            })
                            .thenReturn(true);
                    });
            });
    }

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
