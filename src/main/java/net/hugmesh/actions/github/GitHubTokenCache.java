package net.hugmesh.actions.github;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of the GitHub access_token last seen for a given
 * hugmesh.systems username.
 *
 * <p>Why this exists: the scheduled org-admin revoke reconciler
 * ({@code GitHubOrgAdminReconcileAction}) re-evaluates each current
 * systems-admin against GitHub. GitHub's "what orgs/repos can this
 * user admin?" question can only be asked with <em>that user's</em>
 * token — there is no service-account view of another user's private
 * org/repo admin reach. So we remember the token the registration
 * flow already forwarded for each user (see
 * {@code GitHubOrgAdminGrantAction.evaluateAndGrant}) and replay it on
 * the reconcile tick.
 *
 * <p>Deliberately a non-blocking, process-local, best-effort cache:
 *
 * <ul>
 *   <li>It is NOT the source of truth for anything — losing it (pod
 *       restart) only means a user can't be auto-revoked until they
 *       sign in again and re-seed their token. Grants/revokes
 *       themselves live in Casdoor.</li>
 *   <li>Entries expire after {@code app.actions.gh-org-admin.token-ttl}
 *       (default 24 h). An expired token is treated as "unknown" so the
 *       reconciler leaves that admin untouched rather than acting on a
 *       stale GitHub token (which would 401 and look like "no reach").
 *       This keeps the loop strictly <em>prune-only when we have fresh
 *       evidence</em> and never revokes on missing/stale evidence.</li>
 *   <li>All operations are O(1) map ops on a {@link ConcurrentHashMap}
 *       — no I/O, safe to call from a reactive pipeline.</li>
 * </ul>
 *
 * <p>The key is the Casdoor username (the same {@code hugmeshUsername}
 * the registration controller passes), NOT the {@code <org>/<username>}
 * member id — the reconciler derives the member id itself.
 */
@Component
public class GitHubTokenCache {

    private record Entry(String token, Instant storedAt) {}

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public GitHubTokenCache(
            @Value("${app.actions.gh-org-admin.token-ttl:PT24H}") Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    /** Test seam — inject a fixed {@link Clock} to exercise expiry. */
    GitHubTokenCache(Duration ttl, Clock clock) {
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(24) : ttl;
        this.clock = clock;
    }

    /** Remember (or refresh) the GitHub token for a username. A null or
     *  blank token is ignored — we never cache an empty credential. */
    public void put(String username, String token) {
        if (username == null || username.isBlank() || token == null || token.isBlank()) {
            return;
        }
        tokens.put(username, new Entry(token, clock.instant()));
    }

    /** The freshest non-expired token for a username, or empty when we
     *  have never seen one / it has aged out. Expired entries are
     *  pruned on access. */
    public Optional<String> get(String username) {
        if (username == null) return Optional.empty();
        Entry e = tokens.get(username);
        if (e == null) return Optional.empty();
        if (isExpired(e)) {
            tokens.remove(username, e);
            return Optional.empty();
        }
        return Optional.of(e.token());
    }

    /** Drop a user's token (e.g. once they have been revoked). */
    public void evict(String username) {
        if (username != null) tokens.remove(username);
    }

    /** Snapshot of the usernames with a currently-valid token. Used by
     *  the reconciler to decide which admins it has fresh evidence for.
     *  Expired entries are pruned as a side effect. */
    public Set<String> knownUsernames() {
        tokens.entrySet().removeIf(e -> isExpired(e.getValue()));
        return Set.copyOf(tokens.keySet());
    }

    public int size() {
        tokens.entrySet().removeIf(e -> isExpired(e.getValue()));
        return tokens.size();
    }

    private boolean isExpired(Entry e) {
        return clock.instant().isAfter(e.storedAt().plus(ttl));
    }
}
