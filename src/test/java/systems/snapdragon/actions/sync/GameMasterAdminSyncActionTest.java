package systems.snapdragon.actions.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import systems.snapdragon.actions.casdoor.CasdoorClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * </ul>
 */
class GameMasterAdminSyncActionTest {

    private GameMasterAdminSyncAction newAction(CasdoorClient systems, CasdoorClient games) {
        when(systems.organization()).thenReturn("snapdragon-systems");
        when(games.organization()).thenReturn("threewizards");
        when(systems.label()).thenReturn("systems");
        when(games.label()).thenReturn("games");
        return new GameMasterAdminSyncAction(
                systems, games,
                "game-master", "admin",
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
