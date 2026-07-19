package systems.hugmesh.actions.notify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import systems.hugmesh.actions.casdoor.CasdoorClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RoleChangeNotifier} — the reconcilers' fail-soft, best-effort
 * notification writer:
 *
 * <ul>
 *   <li>Dark writer (no {@link UserNotificationClient} bean) → logged no-op,
 *       {@code skipped} counted, no Casdoor / HTTP calls.</li>
 *   <li>Live writer, resolvable user → delivers with the resolved JWT
 *       subject and the caller's category/labels; honest outcome routed to
 *       the matching counter (delivered / deduped / suppressed).</li>
 *   <li>Unresolvable username → skipped, counted, no delivery attempted.</li>
 *   <li>Delivery failure → ABSORBED (errors counted), never propagates —
 *       a lost notification must never fail the caller's reconcile tick.</li>
 * </ul>
 */
class RoleChangeNotifierTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<UserNotificationClient> providerOf(UserNotificationClient client) {
        ObjectProvider<UserNotificationClient> p = Mockito.mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);
        return p;
    }

    private static RoleChangeNotifier notifier(ObjectProvider<UserNotificationClient> provider,
                                               CasdoorClient systemsCasdoor) {
        return new RoleChangeNotifier(provider, systemsCasdoor, "hugmesh", new SimpleMeterRegistry());
    }

    @Test
    void darkWriter_isNoOp_neverTouchesCasdoor() {
        CasdoorClient systemsCasdoor = Mockito.mock(CasdoorClient.class);
        RoleChangeNotifier n = notifier(providerOf(null), systemsCasdoor);

        StepVerifier.create(n.roleChanged("hugmesh-systems/alice", "admin on hugmesh.games", true,
                        "actions_gm_sync"))
                .verifyComplete();

        verify(systemsCasdoor, never()).getUserId(any());
    }

    @Test
    void resolvableUser_delivers_withResolvedSubjectAndCallerFields() {
        CasdoorClient systemsCasdoor = Mockito.mock(CasdoorClient.class);
        when(systemsCasdoor.getUserId("alice")).thenReturn(Mono.just("uuid-alice"));

        UserNotificationClient client = Mockito.mock(UserNotificationClient.class);
        when(client.deliver(any())).thenReturn(
                Mono.just(new UserNotificationClient.Outcome(true, false, null)));

        RoleChangeNotifier n = notifier(providerOf(client), systemsCasdoor);

        StepVerifier.create(n.roleChanged("hugmesh-systems/alice", "admin on hugmesh.games", true,
                        "actions_gm_sync"))
                .verifyComplete();

        org.mockito.ArgumentCaptor<UserNotificationClient.Delivery> captor =
                org.mockito.ArgumentCaptor.forClass(UserNotificationClient.Delivery.class);
        verify(client, times(1)).deliver(captor.capture());
        UserNotificationClient.Delivery d = captor.getValue();
        assertThat(d.domain()).isEqualTo("hugmesh");
        assertThat(d.userSubject()).isEqualTo("uuid-alice");
        assertThat(d.category()).isEqualTo("role-change");
        assertThat(d.title()).contains("Role granted:").contains("admin on hugmesh.games");
        assertThat(d.sourceTable()).isEqualTo("actions_gm_sync");
        assertThat(d.sourceId()).startsWith("grant:alice:");
    }

    @Test
    void revoke_buildsRevokeTitleAndDirection() {
        CasdoorClient systemsCasdoor = Mockito.mock(CasdoorClient.class);
        when(systemsCasdoor.getUserId("bob")).thenReturn(Mono.just("uuid-bob"));

        UserNotificationClient client = Mockito.mock(UserNotificationClient.class);
        when(client.deliver(any())).thenReturn(
                Mono.just(new UserNotificationClient.Outcome(true, false, null)));

        RoleChangeNotifier n = notifier(providerOf(client), systemsCasdoor);

        StepVerifier.create(n.roleChanged("threewizards/bob", "admin on hugmesh.games", false,
                        "actions_gm_sync"))
                .verifyComplete();

        org.mockito.ArgumentCaptor<UserNotificationClient.Delivery> captor =
                org.mockito.ArgumentCaptor.forClass(UserNotificationClient.Delivery.class);
        verify(client, times(1)).deliver(captor.capture());
        assertThat(captor.getValue().title()).startsWith("Role revoked:");
        assertThat(captor.getValue().sourceId()).startsWith("revoke:bob:");
    }

    @Test
    void unresolvableUsername_skipsWithoutDelivering() {
        CasdoorClient systemsCasdoor = Mockito.mock(CasdoorClient.class);
        when(systemsCasdoor.getUserId("ghost")).thenReturn(Mono.empty());

        UserNotificationClient client = Mockito.mock(UserNotificationClient.class);
        RoleChangeNotifier n = notifier(providerOf(client), systemsCasdoor);

        StepVerifier.create(n.roleChanged("hugmesh-systems/ghost", "admin on hugmesh.games", true,
                        "actions_gm_sync"))
                .verifyComplete();

        verify(client, never()).deliver(any());
    }

    @Test
    void deliveryFailure_isAbsorbed_neverPropagates() {
        CasdoorClient systemsCasdoor = Mockito.mock(CasdoorClient.class);
        when(systemsCasdoor.getUserId("alice")).thenReturn(Mono.just("uuid-alice"));

        UserNotificationClient client = Mockito.mock(UserNotificationClient.class);
        when(client.deliver(any())).thenReturn(Mono.error(new RuntimeException("user-bff unreachable")));

        RoleChangeNotifier n = notifier(providerOf(client), systemsCasdoor);

        // Errors as complete, not error — a lost notification must never fail
        // the caller's reconcile tick.
        StepVerifier.create(n.roleChanged("hugmesh-systems/alice", "admin on hugmesh.games", true,
                        "actions_gm_sync"))
                .verifyComplete();
    }

    @Test
    void nullMember_skipsWithoutTouchingCasdoor() {
        CasdoorClient systemsCasdoor = Mockito.mock(CasdoorClient.class);
        UserNotificationClient client = Mockito.mock(UserNotificationClient.class);
        RoleChangeNotifier n = notifier(providerOf(client), systemsCasdoor);

        StepVerifier.create(n.roleChanged(null, "admin on hugmesh.games", true, "actions_gm_sync"))
                .verifyComplete();

        verify(systemsCasdoor, never()).getUserId(any());
        verify(client, never()).deliver(any());
    }

    @Test
    void usernameOf_stripsOrgPrefix_orPassesThroughBareUsername() {
        assertThat(RoleChangeNotifier.usernameOf("hugmesh-systems/alice")).isEqualTo("alice");
        assertThat(RoleChangeNotifier.usernameOf("alice")).isEqualTo("alice");
        assertThat(RoleChangeNotifier.usernameOf(null)).isNull();
        assertThat(RoleChangeNotifier.usernameOf("hugmesh-systems/")).isNull();
    }
}
