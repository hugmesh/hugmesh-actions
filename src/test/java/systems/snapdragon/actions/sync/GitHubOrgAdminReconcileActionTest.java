package systems.snapdragon.actions.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import systems.snapdragon.actions.casdoor.CasdoorClient;
import systems.snapdragon.actions.github.GitHubClient;
import systems.snapdragon.actions.github.GitHubClientTestFactory;
import systems.snapdragon.actions.github.GitHubTokenCache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GitHubOrgAdminReconcileAction tick contract:
 *
 * <ul>
 *   <li>Revokes admins whose GitHub reach has dropped to zero — only
 *       when we hold a fresh token for them.</li>
 *   <li>Leaves still-eligible admins untouched (idempotent, prune-only).</li>
 *   <li>Skips admins we have no fresh token for — never revokes on
 *       missing evidence.</li>
 *   <li>One user's failure does not abort the rest of the tick.</li>
 *   <li>usernameOf() parses the Casdoor member shape.</li>
 * </ul>
 */
class GitHubOrgAdminReconcileActionTest {

    private GitHubOrgAdminGrantAction grantAction(GitHubClient github, CasdoorClient casdoor,
                                                  GitHubTokenCache cache) {
        when(casdoor.organization()).thenReturn("snapdragon-systems");
        return grantActionReal(github, casdoor, cache);
    }

    /** Build the grant action without stubbing {@code organization()} —
     *  for the regression tests whose CasdoorClient is a real subclass
     *  ({@link FakeCasdoor}), not a Mockito mock. */
    private GitHubOrgAdminGrantAction grantActionReal(GitHubClient github, CasdoorClient casdoor,
                                                      GitHubTokenCache cache) {
        return new GitHubOrgAdminGrantAction(
                github, casdoor, cache,
                "Snapdragon-Systems,Snapdragon", "admin",
                new SimpleMeterRegistry());
    }

    private GitHubOrgAdminReconcileAction reconcile(GitHubOrgAdminGrantAction grant,
                                                    GitHubTokenCache cache) {
        return new GitHubOrgAdminReconcileAction(grant, cache, new SimpleMeterRegistry());
    }

    @Test
    void revokesAdmin_whoLostReach_whenTokenIsCached() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("alice", "alice-tok");

        // alice now has zero reach; admin role currently holds alice + bob
        when(github.snapdragonAdminOrgs(eq("alice-tok"), any())).thenReturn(Mono.just(Set.of()));
        when(casdoor.getRoleMembers("admin"))
                .thenReturn(Mono.just(List.of("snapdragon-systems/alice", "snapdragon-systems/bob")));
        when(casdoor.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(true));

        GitHubOrgAdminGrantAction grant = grantAction(github, casdoor, cache);
        GitHubOrgAdminReconcileAction action = reconcile(grant, cache);

        StepVerifier.create(action.runOnce()).verifyComplete();

        // bob has no cached token → he is skipped, never re-evaluated
        verify(github, never()).snapdragonAdminOrgs(eq("bob"), any());
        // alice is removed; bob stays
        verify(casdoor, times(1)).setRoleMembers(eq("admin"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null
                        && !list.contains("snapdragon-systems/alice")
                        && list.contains("snapdragon-systems/bob")));
        // token evicted after revoke
        assertThat(cache.get("alice")).isEmpty();
    }

    @Test
    void leavesEligibleAdmin_untouched() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("alice", "alice-tok");

        when(github.snapdragonAdminOrgs(eq("alice-tok"), any()))
                .thenReturn(Mono.just(Set.of("Snapdragon")));
        when(casdoor.getRoleMembers("admin"))
                .thenReturn(Mono.just(List.of("snapdragon-systems/alice")));

        GitHubOrgAdminGrantAction grant = grantAction(github, casdoor, cache);
        GitHubOrgAdminReconcileAction action = reconcile(grant, cache);

        StepVerifier.create(action.runOnce()).verifyComplete();

        verify(casdoor, never()).setRoleMembers(anyString(), any());
        assertThat(cache.get("alice")).contains("alice-tok");
    }

    @Test
    void skipsAdmin_withoutCachedToken_neverRevokesOnMissingEvidence() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        // no token cached for carol at all
        when(casdoor.getRoleMembers("admin"))
                .thenReturn(Mono.just(List.of("snapdragon-systems/carol")));

        GitHubOrgAdminGrantAction grant = grantAction(github, casdoor, cache);
        GitHubOrgAdminReconcileAction action = reconcile(grant, cache);

        StepVerifier.create(action.runOnce()).verifyComplete();

        verify(github, never()).snapdragonAdminOrgs(anyString(), any());
        verify(casdoor, never()).setRoleMembers(anyString(), any());
    }

    @Test
    void oneUserFailure_doesNotAbortTheRest() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("alice", "alice-tok");
        cache.put("bob", "bob-tok");

        // alice blows up on the GitHub call; bob has lost reach and must
        // still be revoked despite alice's failure.
        when(github.snapdragonAdminOrgs(eq("alice-tok"), any()))
                .thenReturn(Mono.error(new RuntimeException("boom")));
        when(github.snapdragonAdminOrgs(eq("bob-tok"), any()))
                .thenReturn(Mono.just(Set.of()));
        when(casdoor.getRoleMembers("admin"))
                .thenReturn(Mono.just(List.of("snapdragon-systems/alice", "snapdragon-systems/bob")));
        when(casdoor.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(true));

        GitHubOrgAdminGrantAction grant = grantAction(github, casdoor, cache);
        GitHubOrgAdminReconcileAction action = reconcile(grant, cache);

        StepVerifier.create(action.runOnce()).verifyComplete();

        verify(casdoor, times(1)).setRoleMembers(eq("admin"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null
                        && !list.contains("snapdragon-systems/bob")));
    }

    @Test
    void usernameOf_parsesCasdoorMemberShape() {
        assertThat(GitHubOrgAdminReconcileAction.usernameOf("snapdragon-systems/alice"))
                .isEqualTo("alice");
        assertThat(GitHubOrgAdminReconcileAction.usernameOf("bare")).isEqualTo("bare");
        assertThat(GitHubOrgAdminReconcileAction.usernameOf("snapdragon-systems/")).isNull();
        assertThat(GitHubOrgAdminReconcileAction.usernameOf(null)).isNull();
    }

    // REGRESSION (a) — CONCURRENT MULTI-REVOKE / lost-update race.
    //
    // Two admins (alice + bob) BOTH have zero GitHub reach in the same
    // tick. Backed by REAL mutable Casdoor state whose get/set hop the
    // parallel scheduler — the exact window the validator's reproducer
    // used. The OLD code fanned out revokeIfNoLongerEligible per-user
    // (concurrency=4), so both reconcileOne's did their own
    // get → remove(self) → set: each read the same {alice,bob} snapshot
    // and the two whole-list writes raced, last-writer-wins, leaving one
    // no-longer-eligible admin still in the role. The NEW code evaluates
    // eligibility read-only/concurrently, COLLECTS {alice,bob}, then does
    // ONE getRoleMembers → removeAll → setRoleMembers, so BOTH are gone.
    @Test
    void concurrentMultiRevoke_bothLosersRemoved_noLostUpdate() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("alice", "alice-tok");
        cache.put("bob", "bob-tok");

        // Both admins have lost ALL reach this tick.
        when(github.snapdragonAdminOrgs(eq("alice-tok"), any())).thenReturn(Mono.just(Set.of()));
        when(github.snapdragonAdminOrgs(eq("bob-tok"), any())).thenReturn(Mono.just(Set.of()));

        // Real, mutable Casdoor backing store with scheduler hops on BOTH
        // read and write — this is what makes the lost-update race
        // reproducible under the old per-user RMW.
        FakeCasdoor casdoor = new FakeCasdoor(
                List.of("snapdragon-systems/alice", "snapdragon-systems/bob"));

        GitHubOrgAdminGrantAction grant = grantActionReal(github, casdoor, cache);
        GitHubOrgAdminReconcileAction action = reconcile(grant, cache);

        StepVerifier.create(action.runOnce()).verifyComplete();

        // BOTH no-longer-eligible admins must be gone — no lost update.
        assertThat(casdoor.members())
                .doesNotContain("snapdragon-systems/alice", "snapdragon-systems/bob")
                .isEmpty();
        // The whole tick performed exactly ONE role write (single RMW),
        // not one-per-user — proves the writes were funneled, not raced.
        assertThat(casdoor.writeCount()).isEqualTo(1);
        // Both tokens evicted after revoke.
        assertThat(cache.get("alice")).isEmpty();
        assertThat(cache.get("bob")).isEmpty();
    }

    // REGRESSION (b) — TRANSIENT-ERROR-NO-REVOKE.
    //
    // An org-level-only admin (carol) whose org-membership probe hits a
    // transient NON-404 (a 503) must NOT be revoked this tick — a
    // transient error is inconclusive evidence, treated like a missing
    // token. This drives a REAL GitHubClient (so it exercises the actual
    // isOrgAdmin error path, not a pre-mocked snapdragonAdminOrgs): the
    // org-membership endpoint returns 503, the repos endpoint returns an
    // empty page. The OLD GitHubClient.isOrgAdmin swallowed ANY error to
    // false → reach read empty → carol wrongfully revoked. The NEW one
    // propagates the 503 → carol skipped, no write.
    @Test
    void transientError_orgAdminNotRevoked_thisTick() {
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("carol", "carol-tok");

        // Real client whose org-membership endpoint 503s and whose repos
        // endpoint returns an empty page (no repo-admin reach either).
        GitHubClient github = clientReturning(request -> {
            String path = request.url().getPath();
            if (path.startsWith("/user/memberships/orgs/")) {
                return ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header("Content-Type", "application/json")
                        .body("{\"message\":\"Server Error\"}")
                        .build();
            }
            if (path.equals("/user/repos")) {
                return ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body("[]")
                        .build();
            }
            return ClientResponse.create(HttpStatus.NOT_FOUND).build();
        });

        FakeCasdoor casdoor = new FakeCasdoor(List.of("snapdragon-systems/carol"));

        GitHubOrgAdminGrantAction grant = grantActionReal(github, casdoor, cache);
        GitHubOrgAdminReconcileAction action = reconcile(grant, cache);

        StepVerifier.create(action.runOnce()).verifyComplete();

        // carol survives — a transient error must never cause a revoke.
        assertThat(casdoor.members()).containsExactly("snapdragon-systems/carol");
        assertThat(casdoor.writeCount()).isZero();
        // Token kept so she is re-checked next tick.
        assertThat(cache.get("carol")).contains("carol-tok");
    }

    /** A real {@link GitHubClient} backed by a stubbed exchange function
     *  so tests drive the genuine endpoint/error logic against canned
     *  responses without a live server. */
    private static GitHubClient clientReturning(
            java.util.function.Function<org.springframework.web.reactive.function.client.ClientRequest,
                    ClientResponse> handler) {
        ExchangeFunction exchange = request -> Mono.just(handler.apply(request));
        return GitHubClientTestFactory.withExchange(exchange);
    }

    /**
     * A real, mutable Casdoor stand-in (NOT a Mockito stub) backing the
     * role membership in a thread-safe list. get/set deliberately hop the
     * parallel scheduler with a tiny delay so concurrent per-user
     * read-modify-writes interleave — the reproducer window for the
     * lost-update race. {@code setRoleMembers} replaces the list whole,
     * exactly like the real Casdoor update-role endpoint.
     */
    static final class FakeCasdoor extends CasdoorClient {
        private final CopyOnWriteArrayList<String> members;
        private final AtomicInteger writes = new AtomicInteger();

        FakeCasdoor(List<String> initial) {
            super("http://localhost", "id", "secret", "snapdragon-systems", "systems");
            this.members = new CopyOnWriteArrayList<>(initial);
        }

        @Override
        public Mono<List<String>> getRoleMembers(String roleName) {
            return Mono.fromSupplier(() -> (List<String>) new ArrayList<>(members))
                    .delayElement(Duration.ofMillis(5))
                    .subscribeOn(Schedulers.parallel());
        }

        @Override
        public Mono<Boolean> setRoleMembers(String roleName, List<String> users) {
            return Mono.fromSupplier(() -> {
                        writes.incrementAndGet();
                        members.clear();
                        members.addAll(users);
                        return true;
                    })
                    .delayElement(Duration.ofMillis(5))
                    .subscribeOn(Schedulers.parallel());
        }

        List<String> members() { return new ArrayList<>(members); }
        int writeCount() { return writes.get(); }
    }
}
