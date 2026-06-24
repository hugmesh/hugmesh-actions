package systems.snapdragon.actions.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.config.SecurityConfig;
import systems.snapdragon.actions.sync.GitHubOrgAdminGrantAction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Verifies the reserved-username gate on the registration evaluate endpoint:
 * a reserved username is rejected (422) BEFORE the grant action runs, and a
 * normal username flows through to the (mocked) grant action and returns 200.
 *
 * <p>Driven through the real {@link SecurityConfig} chain (the endpoint is
 * {@code ROLE_ADMIN}-gated), so the client authenticates with an admin
 * {@code mockJwt()}; CSRF is disabled so the POST needs no token.
 */
@WebFluxTest(controllers = RegistrationController.class)
@Import({SecurityConfig.class, systems.snapdragon.actions.security.DomainRegistry.class})
class RegistrationControllerReservedUsernameTest {

    @Autowired WebTestClient client;

    @MockitoBean GitHubOrgAdminGrantAction grantAction;
    // oauth2ResourceServer needs a decoder bean at context start; mockJwt() bypasses it.
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    private WebTestClient asAdmin() {
        return client.mutateWith(mockJwt().authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void reservedUsername_isRejectedWith422_andGrantActionNeverCalled() {
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"snapdragonUsername\":\"admin\"}")
                .exchange()
                .expectStatus().isEqualTo(422);
        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }

    @Test
    void reservedUsername_caseAndSeparatorVariantsAreRejected() {
        // "_Matrix" normalises to "matrix" (reserved); must be rejected too.
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"snapdragonUsername\":\"_Matrix\"}")
                .exchange()
                .expectStatus().isEqualTo(422);
        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }

    @Test
    void normalUsername_passesGateAndReachesGrantAction() {
        when(grantAction.evaluateAndGrant(eq("gho_x"), eq("magus")))
                .thenReturn(Mono.just("snapdragon/magus"));
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"snapdragonUsername\":\"magus\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.memberId").isEqualTo("snapdragon/magus");
        verify(grantAction).evaluateAndGrant("gho_x", "magus");
    }
}
