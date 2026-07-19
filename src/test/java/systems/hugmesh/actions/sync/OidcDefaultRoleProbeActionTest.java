package systems.hugmesh.actions.sync;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import systems.hugmesh.actions.casdoor.CasdoorClient;
import systems.hugmesh.actions.casdoor.CasdoorClient.NewUser;
import systems.hugmesh.actions.oidc.FirstLoginResponse;
import systems.hugmesh.actions.oidc.OidcRoleHookClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contract for {@link OidcDefaultRoleProbeAction}:
 *
 * <ul>
 *   <li>DISABLED → {@code runTick()} is a full no-op (no Casdoor read, no
 *       admin-bff call — verifyNoInteractions on both).</li>
 *   <li>A new user with a mapped signupApplication, within lookback, not yet
 *       seen → exactly one {@code applyDefaultRoles(domain, provider, username)}.</li>
 *   <li>The seen-set prevents a duplicate apply for the same user.</li>
 *   <li>A user with an unmapped signupApplication is skipped.</li>
 *   <li>A user created outside the lookback window is skipped.</li>
 *   <li>An empty get-sorted-users result drives zero applies.</li>
 *   <li>A per-user apply error increments the errors counter and does NOT
 *       abort the tick — remaining users are still processed.</li>
 * </ul>
 */
class OidcDefaultRoleProbeActionTest {

    private static final String PROVIDER_MAP = "app-google=google,app-github=github";

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OidcRoleHookClient> provider(OidcRoleHookClient hook) {
        ObjectProvider<OidcRoleHookClient> p = Mockito.mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(hook);
        return p;
    }

    private OidcDefaultRoleProbeAction newAction(
            boolean enabled, CasdoorClient casdoor, ObjectProvider<OidcRoleHookClient> hookProvider,
            MeterRegistry registry) {
        return new OidcDefaultRoleProbeAction(
                casdoor, hookProvider, enabled,
                "default", 50, Duration.ofMinutes(10), PROVIDER_MAP, registry);
    }

    private static String nowIso() {
        return Instant.now().toString();
    }

    @Test
    void disabled_runTick_isFullNoop() {
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        OidcRoleHookClient hook = Mockito.mock(OidcRoleHookClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OidcRoleHookClient> hookProvider = Mockito.mock(ObjectProvider.class);

        OidcDefaultRoleProbeAction action =
                newAction(false, casdoor, hookProvider, new SimpleMeterRegistry());

        action.runTick();

        verifyNoInteractions(casdoor);
        verifyNoInteractions(hook);
        // Disabled action never even asks the provider for the bean.
        verifyNoInteractions(hookProvider);
    }

    @Test
    void enabledButHookBeanAbsent_runTick_isNoop() {
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OidcRoleHookClient> hookProvider = Mockito.mock(ObjectProvider.class);
        when(hookProvider.getIfAvailable()).thenReturn(null);

        OidcDefaultRoleProbeAction action =
                newAction(true, casdoor, hookProvider, new SimpleMeterRegistry());

        action.runTick();

        verifyNoInteractions(casdoor);
    }

    @Test
    void newMappedUser_appliesDefaultRolesOnce_withExactArgs() {
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        OidcRoleHookClient hook = Mockito.mock(OidcRoleHookClient.class);
        when(casdoor.listNewUsers(50)).thenReturn(Mono.just(List.of(
                new NewUser("alice", "app-google", nowIso()))));
        when(hook.applyDefaultRoles(any(), any(), any()))
                .thenReturn(Mono.just(new FirstLoginResponse(List.of("user"))));

        OidcDefaultRoleProbeAction action =
                newAction(true, casdoor, provider(hook), new SimpleMeterRegistry());

        action.runOnce(hook).block();

        verify(hook, times(1)).applyDefaultRoles(eq("default"), eq("google"), eq("alice"));
        assertThat(action.seenSnapshot()).contains("alice");
    }

    @Test
    void alreadySeenUser_isNotAppliedTwice() {
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        OidcRoleHookClient hook = Mockito.mock(OidcRoleHookClient.class);
        when(casdoor.listNewUsers(50)).thenReturn(Mono.just(List.of(
                new NewUser("alice", "app-google", nowIso()))));
        when(hook.applyDefaultRoles(any(), any(), any()))
                .thenReturn(Mono.just(new FirstLoginResponse(List.of("user"))));

        OidcDefaultRoleProbeAction action =
                newAction(true, casdoor, provider(hook), new SimpleMeterRegistry());

        action.runOnce(hook).block();
        action.runOnce(hook).block(); // second tick, same user

        verify(hook, times(1)).applyDefaultRoles(eq("default"), eq("google"), eq("alice"));
    }

    @Test
    void unmappedSignupApplication_isSkipped() {
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        OidcRoleHookClient hook = Mockito.mock(OidcRoleHookClient.class);
        when(casdoor.listNewUsers(50)).thenReturn(Mono.just(List.of(
                new NewUser("alice", "app-unknown", nowIso()))));

        OidcDefaultRoleProbeAction action =
                newAction(true, casdoor, provider(hook), new SimpleMeterRegistry());

        action.runOnce(hook).block();

        verify(hook, never()).applyDefaultRoles(any(), any(), any());
        assertThat(action.seenSnapshot()).doesNotContain("alice");
    }

    @Test
    void userOutsideLookback_isSkipped() {
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        OidcRoleHookClient hook = Mockito.mock(OidcRoleHookClient.class);
        String old = Instant.now().minus(Duration.ofHours(1)).toString();
        when(casdoor.listNewUsers(50)).thenReturn(Mono.just(List.of(
                new NewUser("alice", "app-google", old))));

        OidcDefaultRoleProbeAction action =
                newAction(true, casdoor, provider(hook), new SimpleMeterRegistry());

        action.runOnce(hook).block();

        verify(hook, never()).applyDefaultRoles(any(), any(), any());
    }

    @Test
    void emptyUserList_zeroApplies() {
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        OidcRoleHookClient hook = Mockito.mock(OidcRoleHookClient.class);
        when(casdoor.listNewUsers(50)).thenReturn(Mono.just(List.of()));

        OidcDefaultRoleProbeAction action =
                newAction(true, casdoor, provider(hook), new SimpleMeterRegistry());

        action.runOnce(hook).block();

        verify(hook, never()).applyDefaultRoles(any(), any(), any());
    }

    @Test
    void perUserError_doesNotAbortTick_othersStillProcessed() {
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        OidcRoleHookClient hook = Mockito.mock(OidcRoleHookClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(casdoor.listNewUsers(50)).thenReturn(Mono.just(List.of(
                new NewUser("alice", "app-google", nowIso()),
                new NewUser("bob", "app-github", nowIso()))));
        // alice errors (despite the client normally failing open, defend the tick),
        // bob succeeds.
        when(hook.applyDefaultRoles(eq("default"), eq("google"), eq("alice")))
                .thenReturn(Mono.error(new RuntimeException("boom")));
        when(hook.applyDefaultRoles(eq("default"), eq("github"), eq("bob")))
                .thenReturn(Mono.just(new FirstLoginResponse(List.of("user"))));

        OidcDefaultRoleProbeAction action =
                newAction(true, casdoor, provider(hook), registry);

        action.runOnce(hook).block();

        verify(hook, times(1)).applyDefaultRoles(eq("default"), eq("google"), eq("alice"));
        verify(hook, times(1)).applyDefaultRoles(eq("default"), eq("github"), eq("bob"));
        // bob applied; alice failed (errors incremented, un-marked for retry).
        assertThat(registry.get("actions.oidc_default_roles.applied").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("actions.oidc_default_roles.errors").counter().count()).isEqualTo(1.0);
        assertThat(action.seenSnapshot()).contains("bob").doesNotContain("alice");
    }

    @Test
    void parseProviderMap_dropsMalformedEntries() {
        var map = OidcDefaultRoleProbeAction.parseProviderMap(
                "app-google=google, =bad, app-github=github, nokey, trailing=");
        assertThat(map).containsEntry("app-google", "google")
                .containsEntry("app-github", "github")
                .hasSize(2);
    }

    @Test
    void parseInstant_handlesIsoAndOffset_andNullOnGarbage() {
        assertThat(OidcDefaultRoleProbeAction.parseInstant("2026-06-19T10:00:00Z")).isNotNull();
        assertThat(OidcDefaultRoleProbeAction.parseInstant("2026-06-19T10:00:00+02:00")).isNotNull();
        assertThat(OidcDefaultRoleProbeAction.parseInstant("not-a-date")).isNull();
        assertThat(OidcDefaultRoleProbeAction.parseInstant(null)).isNull();
    }
}
