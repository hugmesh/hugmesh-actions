package systems.snapdragon.actions.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.config.SecurityConfig;
import systems.snapdragon.actions.federation.FederationUsernameClient;
import systems.snapdragon.actions.federation.UsernameAvailability;
import systems.snapdragon.actions.sync.GitHubOrgAdminGrantAction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Verifies the federation confusable/availability gate on the registration
 * evaluate endpoint when the gate is configured ON (a {@link FederationUsernameClient}
 * bean is present):
 *
 * <ul>
 *   <li>a candidate the registry reports as colliding (exact/confusable/reserved)
 *       is rejected 422 BEFORE the grant action runs;</li>
 *   <li>an available candidate flows through to the (mocked) grant action;</li>
 *   <li>the gate runs only AFTER the local exact reserved fast-path (a locally
 *       reserved label is rejected without even consulting federation).</li>
 * </ul>
 *
 * <p>The mocked {@link FederationUsernameClient} is supplied by a {@link TestConfiguration}
 * so the controller's {@code ObjectProvider} resolves it — mirroring the in-cluster
 * {@code app.federation.username-check.enabled=true} wiring.
 */
@WebFluxTest(controllers = RegistrationController.class)
@Import({SecurityConfig.class, systems.snapdragon.commons.security.DomainRegistry.class,
        RegistrationControllerConfusableGateTest.GateConfig.class})
class RegistrationControllerConfusableGateTest {

    @Autowired WebTestClient client;
    @Autowired FederationUsernameClient federationUsername;

    @MockitoBean GitHubOrgAdminGrantAction grantAction;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    @TestConfiguration
    static class GateConfig {
        @Bean
        FederationUsernameClient federationUsernameClient() {
            return mock(FederationUsernameClient.class);
        }
    }

    @BeforeEach
    void resetSharedMock() {
        // The FederationUsernameClient bean is a @TestConfiguration singleton
        // (reused across methods), so reset its interaction log between tests —
        // otherwise a never()-verify would see a prior test's call.
        org.mockito.Mockito.reset(federationUsername);
    }

    private WebTestClient asAdmin() {
        return client.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void confusableCandidate_isRejected422_andGrantActionNeverCalled() {
        when(federationUsername.availability(eq("4dmin")))
                .thenReturn(Mono.just(new UsernameAvailability(
                        false, true, "confusable", "admin", "admin")));

        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"snapdragonUsername\":\"4dmin\"}")
                .exchange()
                .expectStatus().isEqualTo(422);

        verify(federationUsername).availability("4dmin");
        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }

    @Test
    void availableCandidate_passesGateAndReachesGrantAction() {
        when(federationUsername.availability(eq("magus")))
                .thenReturn(Mono.just(new UsernameAvailability(
                        true, false, null, null, "magus")));
        when(grantAction.evaluateAndGrant(eq("gho_x"), eq("magus")))
                .thenReturn(Mono.just("snapdragon/magus"));

        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"snapdragonUsername\":\"magus\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.memberId").isEqualTo("snapdragon/magus");

        verify(federationUsername).availability("magus");
        verify(grantAction).evaluateAndGrant("gho_x", "magus");
    }

    @Test
    void failClosed_collideTrueWithUnknownReason_isRejected422() {
        // Simulates the client's fail-closed verdict (federation unreachable).
        when(federationUsername.availability(eq("alice")))
                .thenReturn(Mono.just(UsernameAvailability.collideUnknown()));

        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"snapdragonUsername\":\"alice\"}")
                .exchange()
                .expectStatus().isEqualTo(422);

        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }

    @Test
    void locallyReservedLabel_isRejected422_withoutConsultingFederation() {
        // The local exact reserved fast-path fires first, so federation is never
        // asked for a label we already know is reserved.
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"snapdragonUsername\":\"admin\"}")
                .exchange()
                .expectStatus().isEqualTo(422);

        verify(federationUsername, never()).availability(any());
        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }
}
