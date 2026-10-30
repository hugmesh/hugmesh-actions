package net.hugmesh.actions.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import net.hugmesh.actions.casdoor.FakeCasdoor;
import net.hugmesh.actions.notify.RoleChangeNotifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GameMasterAdminSyncAction against a real CasdoorClient over {@link FakeCasdoor}
 * (which mimics the measured Casdoor 3.80.0 behaviour):
 *
 * <ul>
 *   <li>G12: only the owned role is ever written; the realm's own admin role and
 *       every native admin in it stay untouched; an unowned role name is refused.</li>
 *   <li>G13: users map through the explicit link, never by username; a same-name
 *       unlinked games user is not added; no dangling name is ever written.</li>
 *   <li>G14: the write preserves every other role column and is confirmed by
 *       read-back.</li>
 *   <li>A read error fails the tick before any write.</li>
 * </ul>
 */
class GameMasterAdminSyncActionTest {

    private static final String SYS = "hugmesh";
    private static final String TW = "threewizards";
    private static final String OWNED = "mesh-games-admin";
    private static final String SYS_ROLE = "ROLE_GAMES_ADMIN";

    private FakeCasdoor systems;
    private FakeCasdoor games;
    private RoleChangeNotifier notifier;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        systems = new FakeCasdoor(SYS);
        games = new FakeCasdoor(TW)
                .role("admin", true, TW + "/seeded-admin", TW + "/console-granted")
                .role(OWNED, true);
        notifier = Mockito.mock(RoleChangeNotifier.class);
        when(notifier.roleChangedForSubject(any(), any(), anyString(), anyBoolean(), anyString()))
                .thenReturn(Mono.empty());
        registry = new SimpleMeterRegistry();
    }

    private GameMasterAdminSyncAction action(String gamesRole) {
        return new GameMasterAdminSyncAction(systems.client(), games.client(),
                SYS_ROLE, gamesRole, "meshSub", notifier, registry);
    }

    private double count(String name) { return registry.counter(name).count(); }

    private void linkedAlice() {
        systems.user("alice", "sub-alice", null);
        games.user("alice", "games-id-alice", Map.of("meshSub", "sub-alice"));
    }

    @Test
    void noChange_isNoop() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();
        games.role(OWNED, true, TW + "/alice");

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.roleWrites).isEmpty();
        assertThat(count("actions.gm_sync.unchanged")).isEqualTo(1.0);
        verify(notifier, never()).roleChangedForSubject(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void linkedMember_isAdded_underItsGamesName_andNotifiedOnItsMeshSubject() {
        systems.role(SYS_ROLE, true, SYS + "/bob").user("bob", "sub-bob", null);
        games.user("bobby-the-player", "games-id-bob", Map.of("meshSub", "sub-bob"));

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).containsExactly(TW + "/bobby-the-player");
        assertThat(count("actions.gm_sync.added")).isEqualTo(1.0);
        verify(notifier, times(1)).roleChangedForSubject(
                eq("sub-bob"), eq(TW + "/bobby-the-player"), anyString(), eq(true),
                eq(GameMasterAdminSyncAction.SOURCE_TABLE));
    }

    @Test
    void removedMember_isStripped_andNotifiedOnItsMeshSubject() {
        systems.role(SYS_ROLE, true);
        games.user("dropped", "games-id-d", Map.of("meshSub", "sub-dropped"));
        games.role(OWNED, true, TW + "/dropped");

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.removed")).isEqualTo(1.0);
        verify(notifier, times(1)).roleChangedForSubject(
                eq("sub-dropped"), eq(TW + "/dropped"), anyString(), eq(false),
                eq(GameMasterAdminSyncAction.SOURCE_TABLE));
    }

    // ---- G12 ----

    @Test
    void reconcile_neverWritesTheRealmsOwnAdminRole() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.roleWrites).containsExactly(TW + "/" + OWNED);
        assertThat(games.members("admin"))
                .containsExactly(TW + "/seeded-admin", TW + "/console-granted");
    }

    @Test
    void unownedGamesRole_isRefused_withoutReadingOrWritingAnything() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();

        for (String refused : new String[] {"admin", "ADMIN", "player", "mod", "publisher",
                "uploader", "play-three-wizards", "mesh-", ""}) {
            games.role(refused, true, TW + "/native-member");
            StepVerifier.create(action(refused).runOnce()).expectError(IllegalStateException.class).verify();
            assertThat(games.members(refused)).containsExactly(TW + "/native-member");
        }

        assertThat(games.requests).isEmpty();
        assertThat(systems.requests).isEmpty();
    }

    @Test
    void isOwnedRole_rule() {
        assertThat(GameMasterAdminSyncAction.isOwnedRole("mesh-games-admin")).isTrue();
        assertThat(GameMasterAdminSyncAction.isOwnedRole("admin")).isFalse();
        assertThat(GameMasterAdminSyncAction.isOwnedRole("publisher")).isFalse();
        assertThat(GameMasterAdminSyncAction.isOwnedRole("mesh-")).isFalse();
        assertThat(GameMasterAdminSyncAction.isOwnedRole(null)).isFalse();
    }

    // ---- G13 ----

    @Test
    void sameNameUnlinkedGamesUser_isNotAdded() {
        systems.role(SYS_ROLE, true, SYS + "/alice").user("alice", "sub-alice", null);
        // An unrelated person who self-signed-up as "alice" on the games realm.
        games.user("alice", "games-id-other", null);

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.roleWrites).isEmpty();
        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.unlinked")).isEqualTo(1.0);
    }

    @Test
    void sameNameUserLinkedToSomeoneElse_isNotAdded() {
        systems.role(SYS_ROLE, true, SYS + "/alice").user("alice", "sub-alice", null);
        games.user("alice", "games-id-other", Map.of("meshSub", "sub-carol"));

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
    }

    @Test
    void missingGamesAccount_writesNoDanglingName() {
        systems.role(SYS_ROLE, true, SYS + "/ghost").user("ghost", "sub-ghost", null);

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.roleWrites).isEmpty();
        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.unlinked")).isEqualTo(1.0);
    }

    @Test
    void danglingSystemsMember_writesNothing() {
        // In the systems role, but no such systems user.
        systems.role(SYS_ROLE, true, SYS + "/nobody");
        // A games user carrying that name must not be picked up by name.
        games.user("nobody", "games-id-n", null);

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.unlinked")).isEqualTo(1.0);
    }

    @Test
    void propertySubstringMatch_isNotALink() {
        systems.role(SYS_ROLE, true, SYS + "/alice").user("alice", "sub", null);
        // Casdoor's LIKE filter returns these; neither is an exact meshSub match.
        games.user("mallory", "g1", Map.of("meshSub", "sub-of-someone-else"));
        games.user("eve", "g2", Map.of("note", "sub"));

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
    }

    @Test
    void twoGamesAccountsClaimingOneSubject_linksNeither() {
        systems.role(SYS_ROLE, true, SYS + "/alice").user("alice", "sub-alice", null);
        games.user("a1", "g1", Map.of("meshSub", "sub-alice"));
        games.user("a2", "g2", Map.of("meshSub", "sub-alice"));

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.unlinked")).isEqualTo(1.0);
    }

    @Test
    void linkClaimedByAForbiddenAccountToo_linksNeither() {
        systems.role(SYS_ROLE, true, SYS + "/alice").user("alice", "sub-alice", null);
        games.user("a1", "g1", Map.of("meshSub", "sub-alice"));
        games.user("a2", "g2", Map.of("meshSub", "sub-alice"), true);

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.unlinked")).isEqualTo(1.0);
    }

    @Test
    void forbiddenAccounts_areNotLinked() {
        systems.role(SYS_ROLE, true, SYS + "/banned", SYS + "/ok")
                .user("banned", "sub-banned", null, true)
                .user("ok", "sub-ok", null);
        games.user("banned", "g1", Map.of("meshSub", "sub-banned"));
        games.user("ok", "g2", Map.of("meshSub", "sub-ok"), true);

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.unlinked")).isEqualTo(2.0);
    }

    @Test
    void memberOfAnotherOrg_isSkipped() {
        systems.role(SYS_ROLE, true, "other-org/alice");
        linkedAlice();

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.unlinked")).isEqualTo(1.0);
    }

    // ---- G14 + failure handling ----

    @Test
    void write_preservesEveryOtherRoleColumn() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        Map<String, Object> after = games.roleObject(OWNED);
        assertThat(after.get("isEnabled")).isEqualTo(true);
        assertThat(after.get("displayName")).isEqualTo("DN-" + OWNED);
        assertThat(after.get("description")).isEqualTo("seeded");
        assertThat(after.get("domains")).isEqualTo(java.util.List.of("default"));
        assertThat(after.get("users")).isEqualTo(java.util.List.of(TW + "/alice"));
    }

    @Test
    void secondTick_afterAWrite_isANoop() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();
        GameMasterAdminSyncAction a = action(OWNED);

        StepVerifier.create(a.runOnce()).verifyComplete();
        StepVerifier.create(a.runOnce()).verifyComplete();

        assertThat(games.roleWrites).hasSize(1);
        assertThat(count("actions.gm_sync.unchanged")).isEqualTo(1.0);
    }

    @Test
    void missingOwnedRole_failsTheTick_notifiesNobody() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();

        StepVerifier.create(action("mesh-never-seeded").runOnce())
                .expectError(IllegalStateException.class).verify();

        assertThat(games.roleWrites).isEmpty();
        verify(notifier, never()).roleChangedForSubject(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void unconfirmedWrite_failsTheTick_notifiesNobody() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();
        games.dropWrites = true;

        StepVerifier.create(action(OWNED).runOnce()).expectError(IllegalStateException.class).verify();

        assertThat(count("actions.gm_sync.added")).isZero();
        verify(notifier, never()).roleChangedForSubject(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void transientSystemsReadError_failsTheTick_andRevokesNobody() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();
        games.role(OWNED, true, TW + "/alice");
        systems.failPath = "/api/get-user";

        StepVerifier.create(action(OWNED).runOnce()).expectError().verify();

        assertThat(games.roleWrites).isEmpty();
        assertThat(games.members(OWNED)).containsExactly(TW + "/alice");
    }

    @Test
    void transientGamesLookupError_failsTheTick_andRevokesNobody() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();
        games.role(OWNED, true, TW + "/alice");
        games.failPath = "/api/get-users";

        StepVerifier.create(action(OWNED).runOnce()).expectError().verify();

        assertThat(games.roleWrites).isEmpty();
        assertThat(games.members(OWNED)).containsExactly(TW + "/alice");
    }

    @Test
    void rejectedSystemsCredential_failsTheTick_andRevokesNobody() {
        // HTTP 200 status:"error": the old client read this as an empty member
        // list, so a rotated secret emptied the games-side role on the next tick.
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();
        games.role(OWNED, true, TW + "/alice");
        systems.rejectAllWith = "Unauthorized operation";

        StepVerifier.create(action(OWNED).runOnce()).expectError(IllegalStateException.class).verify();

        assertThat(games.roleWrites).isEmpty();
        assertThat(games.members(OWNED)).containsExactly(TW + "/alice");
    }

    @Test
    void rejectedGamesCredential_failsTheTick() {
        systems.role(SYS_ROLE, true, SYS + "/alice");
        linkedAlice();
        games.rejectAllWith = "Unauthorized operation";

        StepVerifier.create(action(OWNED).runOnce()).expectError(IllegalStateException.class).verify();

        assertThat(games.roleWrites).isEmpty();
    }

    @Test
    void unseededOwnedRole_failsTheTick_evenWithNothingToWrite() {
        systems.role(SYS_ROLE, true);

        StepVerifier.create(action("mesh-never-seeded").runOnce())
                .expectError(IllegalStateException.class).verify();

        assertThat(count("actions.gm_sync.unchanged")).isZero();
    }

    @Test
    void revokeNotificationLookupFailure_doesNotFailTheTick() {
        systems.role(SYS_ROLE, true);
        games.role(OWNED, true, TW + "/dropped");
        games.failPath = "/api/get-user";  // also matches get-users; no lookups are needed here

        StepVerifier.create(action(OWNED).runOnce()).verifyComplete();

        assertThat(games.members(OWNED)).isEmpty();
        assertThat(count("actions.gm_sync.removed")).isEqualTo(1.0);
    }
}
