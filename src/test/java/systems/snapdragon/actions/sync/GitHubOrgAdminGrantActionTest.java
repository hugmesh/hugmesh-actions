package systems.snapdragon.actions.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import systems.snapdragon.actions.casdoor.CasdoorClient;
import systems.snapdragon.actions.github.GitHubClient;

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
        when(casdoor.organization()).thenReturn("snapdragon-systems");
        return new GitHubOrgAdminGrantAction(
                github, casdoor,
                "Snapdragon-Systems,Snapdragon",
                "admin",
                new SimpleMeterRegistry());
    }

    @Test
    void parseCsv_trimsAndDedupes() {
        Set<String> s = GitHubOrgAdminGrantAction.parseCsv("Snapdragon-Systems, Snapdragon ,Snapdragon");
        assertThat(s).containsExactlyInAnyOrder("Snapdragon-Systems", "Snapdragon");
    }

    @Test
    void grantAdds_whenUserHasOrgAdminReach_andNotAlreadyMember() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.snapdragonAdminOrgs(eq("ghtok"), any()))
                .thenReturn(Mono.just(Set.of("Snapdragon-Systems")));
        when(casdoor.getRoleMembers("admin")).thenReturn(Mono.just(List.of()));
        when(casdoor.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(true));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.evaluateAndGrant("ghtok", "alice"))
                .expectNext("snapdragon-systems/alice")
                .verifyComplete();

        verify(casdoor, times(1)).setRoleMembers(eq("admin"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null
                        && list.contains("snapdragon-systems/alice")));
    }

    @Test
    void grantIsNoop_whenAlreadyMember() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.snapdragonAdminOrgs(anyString(), any()))
                .thenReturn(Mono.just(Set.of("Snapdragon")));
        when(casdoor.getRoleMembers("admin"))
                .thenReturn(Mono.just(List.of("snapdragon-systems/alice")));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.evaluateAndGrant("ghtok", "alice"))
                .expectNext("snapdragon-systems/alice")
                .verifyComplete();
        verify(casdoor, never()).setRoleMembers(anyString(), any());
    }

    @Test
    void denied_whenUserHasNoSnapdragonAdminReach() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.snapdragonAdminOrgs(anyString(), any())).thenReturn(Mono.just(Set.of()));

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
        when(github.snapdragonAdminOrgs(anyString(), any())).thenReturn(Mono.just(Set.of()));
        when(casdoor.getRoleMembers("admin"))
                .thenReturn(Mono.just(List.of("snapdragon-systems/alice", "snapdragon-systems/bob")));
        when(casdoor.setRoleMembers(eq("admin"), any())).thenReturn(Mono.just(true));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.revokeIfNoLongerEligible("ghtok", "alice"))
                .expectNext(true)
                .verifyComplete();

        verify(casdoor, times(1)).setRoleMembers(eq("admin"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null
                        && !list.contains("snapdragon-systems/alice")
                        && list.contains("snapdragon-systems/bob")));
    }

    @Test
    void revokeIsNoop_whenUserStillHasReach() {
        GitHubClient github = Mockito.mock(GitHubClient.class);
        CasdoorClient casdoor = Mockito.mock(CasdoorClient.class);
        when(github.snapdragonAdminOrgs(anyString(), any())).thenReturn(Mono.just(Set.of("Snapdragon")));

        GitHubOrgAdminGrantAction action = newAction(github, casdoor);
        StepVerifier.create(action.revokeIfNoLongerEligible("ghtok", "alice"))
                .expectNext(false)
                .verifyComplete();
        verify(casdoor, never()).setRoleMembers(anyString(), any());
    }
}
