package systems.hugmesh.actions.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import systems.hugmesh.actions.casdoor.CasdoorClient;
import systems.hugmesh.actions.github.GitHubClient;
import systems.hugmesh.actions.github.GitHubTokenCache;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubOrgAdminGrantActionTest {

    private GitHubOrgAdminGrantAction newAction(GitHubClient github, CasdoorClient casdoor) {
        return newAction(github, casdoor, true);
    }

    private GitHubOrgAdminGrantAction newAction(GitHubClient github, CasdoorClient casdoor, boolean enabled) {
        when(casdoor.organization()).thenReturn("hugmesh-systems");
        return new GitHubOrgAdminGrantAction(
                github, casdoor,
                new GitHubTokenCache(Duration.ofHours(24)),
                "HugMesh,HugMesh",
                "admin",
                enabled,
                new SimpleMeterRegistry());
    }

    @Test
    void disabled_returns404() {
        GitHubOrgAdminGrantAction action = newAction(
                Mockito.mock(GitHubClient.class),
                Mockito.mock(CasdoorClient.class),
                false);
        StepVerifier.create(action.evaluateAndGrant("ghtok", "alice"))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
    }

    @Test
    void parseCsv_trimsAndDedupes() {
        Set<String> s = GitHubOrgAdminGrantAction.parseCsv("Acme, Acme2 ,Acme");
        assertThat(s).containsExactlyInAnyOrder("Acme", "Acme2");
    }

    @Test
    void grantAdds_whenUserHasOrgAdminReach_andNotAlreadyMember() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.hugmeshAdminOrgs(eq("ghtok"), any()))
                .thenReturn(Mono.just(Set.of("HugMesh")));
        when(casdoor.getRoleMembers("admin")).thenReturn(Mono.just(List.of()));
        when(casdoor.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(true));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.evaluateAndGrant("ghtok", "alice"))
                .expectNext("hugmesh-systems/alice")
                .verifyComplete();

        verify(casdoor, times(1)).setRoleMembers(eq("admin"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null
                        && list.contains("hugmesh-systems/alice")));
    }

    @Test
    void grantIsNoop_whenAlreadyMember() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.hugmeshAdminOrgs(anyString(), any()))
                .thenReturn(Mono.just(Set.of("HugMesh")));
        when(casdoor.getRoleMembers("admin"))
                .thenReturn(Mono.just(List.of("hugmesh-systems/alice")));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.evaluateAndGrant("ghtok", "alice"))
                .expectNext("hugmesh-systems/alice")
                .verifyComplete();
        verify(casdoor, never()).setRoleMembers(anyString(), any());
    }

    @Test
    void denied_whenUserHasNoHugMeshAdminReach() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.hugmeshAdminOrgs(anyString(), any())).thenReturn(Mono.just(Set.of()));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.evaluateAndGrant("ghtok", "bob"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && ((ResponseStatusException) e).getStatusCode().value() == 403)
                .verify();
        verify(casdoor, never()).setRoleMembers(anyString(), any());
    }

    @Test
    void revokeFires_whenUserLosesReachWhileStillAdminInCasdoor() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.hugmeshAdminOrgs(anyString(), any())).thenReturn(Mono.just(Set.of()));
        when(casdoor.getRoleMembers("admin"))
                .thenReturn(Mono.just(List.of("hugmesh-systems/alice", "hugmesh-systems/bob")));
        when(casdoor.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(true));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.revokeIfNoLongerEligible("ghtok", "alice"))
                .expectNext(true)
                .verifyComplete();

        verify(casdoor, times(1)).setRoleMembers(eq("admin"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null
                        && !list.contains("hugmesh-systems/alice")
                        && list.contains("hugmesh-systems/bob")));
    }

    @Test
    void revokeIsNoop_whenUserStillHasReach() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.hugmeshAdminOrgs(anyString(), any())).thenReturn(Mono.just(Set.of("HugMesh")));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.revokeIfNoLongerEligible("ghtok", "alice"))
                .expectNext(false)
                .verifyComplete();
        verify(casdoor, never()).setRoleMembers(anyString(), any());
    }
}
