package systems.snapdragon.actions.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import systems.snapdragon.actions.casdoor.CasdoorClient;
import systems.snapdragon.actions.notify.RoleChangeNotifier;

import java.util.List;

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
 * GameMasterAdminSyncAction reconcile() contract:
 *
 * <ul>
 *   <li>No-op when desired == current.</li>
 *   <li>Adds missing members.</li>
 *   <li>Removes stale members.</li>
 *   <li>Rewrites the organization prefix between sides.</li>
 *   <li>Counter increments correspond to the diff.</li>
 *   <li>A confirmed write notifies every added/removed member;
 *       a no-op tick notifies nobody.</li>
 * </ul>
 */
class GameMasterAdminSyncActionTest {

    private RoleChangeNotifier notifier;

    private GameMasterAdminSyncAction newAction(CasdoorClient systems, CasdoorClient games) {
        when(systems.organization()).thenReturn("snapdragon-systems");
        when(games.organization()).thenReturn("threewizards");
        when(systems.label()).thenReturn("systems");
        when(games.label()).thenReturn("games");
        notifier = Mockito.mock(RoleChangeNotifier.class);
        when(notifier.roleChanged(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(Mono.empty());
        return new GameMasterAdminSyncAction(
                systems, games,
                "game-master", "admin",
                notifier,
                new SimpleMeterRegistry());
    }

    @Test
    void noChange_isNoop() {
        CasdoorClient systems = Mockito.mock(CasdoorClient.class);
        CasdoorClient games   = Mockito.mock(CasdoorClient.class);
        GameMasterAdminSyncAction action = newAction(systems, games);

        StepVerifier.create(action.reconcile(
                List.of("snapdragon-systems/alice"),
                List.of("threewizards/alice")))
            .verifyComplete();
        verify(games, never()).setRoleMembers(any(), any());
        verify(notifier, never()).roleChanged(any(), any(), anyBoolean(), any());
    }

    @Test
    void newGameMaster_isAddedToGamesAdmin() {
        CasdoorClient systems = Mockito.mock(CasdoorClient.class);
        CasdoorClient games   = Mockito.mock(CasdoorClient.class);
        when(games.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(true));
        GameMasterAdminSyncAction action = newAction(systems, games);

        StepVerifier.create(action.reconcile(
                List.of("snapdragon-systems/alice", "snapdragon-systems/bob"),
                List.of("threewizards/alice")))
            .verifyComplete();

        verify(games, times(1)).setRoleMembers(eq("admin"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null
                        && list.contains("threewizards/alice")
                        && list.contains("threewizards/bob")
                        && list.size() == 2));
        verify(notifier, times(1)).roleChanged(
                eq("threewizards/bob"), eq("admin on snapdragon.games"), eq(true),
                eq(GameMasterAdminSyncAction.SOURCE_TABLE));
        verify(notifier, never()).roleChanged(any(), any(), eq(false), any());
    }

    @Test
    void removedGameMaster_isStrippedFromGamesAdmin() {
        CasdoorClient systems = Mockito.mock(CasdoorClient.class);
        CasdoorClient games   = Mockito.mock(CasdoorClient.class);
        when(games.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(true));
        GameMasterAdminSyncAction action = newAction(systems, games);

        StepVerifier.create(action.reconcile(
                List.of("snapdragon-systems/alice"),
                List.of("threewizards/alice", "threewizards/dropped")))
            .verifyComplete();

        verify(games, times(1)).setRoleMembers(eq("admin"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null
                        && list.contains("threewizards/alice")
                        && !list.contains("threewizards/dropped")
                        && list.size() == 1));
        verify(notifier, times(1)).roleChanged(
                eq("threewizards/dropped"), eq("admin on snapdragon.games"), eq(false),
                eq(GameMasterAdminSyncAction.SOURCE_TABLE));
        verify(notifier, never()).roleChanged(any(), any(), eq(true), any());
    }

    @Test
    void unconfirmedWrite_notifiesNobody() {
        CasdoorClient systems = Mockito.mock(CasdoorClient.class);
        CasdoorClient games   = Mockito.mock(CasdoorClient.class);
        // Casdoor reports the write did NOT take (false) — never notify on
        // an unconfirmed change.
        when(games.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(false));
        GameMasterAdminSyncAction action = newAction(systems, games);

        StepVerifier.create(action.reconcile(
                List.of("snapdragon-systems/alice", "snapdragon-systems/bob"),
                List.of("threewizards/alice")))
            .verifyComplete();

        verify(notifier, never()).roleChanged(any(), any(), anyBoolean(), any());
    }

    @Test
    void swapOrganization_mapsCanonicalShape() {
        assertThat(GameMasterAdminSyncAction.swapOrganization(
                "snapdragon-systems/alice", "snapdragon-systems", "threewizards"))
            .isEqualTo("threewizards/alice");
    }

    @Test
    void swapOrganization_prependsWhenBareUsername() {
        assertThat(GameMasterAdminSyncAction.swapOrganization(
                "alice", "snapdragon-systems", "threewizards"))
            .isEqualTo("threewizards/alice");
    }
}
