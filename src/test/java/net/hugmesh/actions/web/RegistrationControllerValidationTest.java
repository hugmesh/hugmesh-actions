package net.hugmesh.actions.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import net.hugmesh.actions.config.SecurityConfig;
import net.hugmesh.actions.sync.GitHubOrgAdminGrantAction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Bean-validation contract on {@code POST /api/registration/github-org-admin/evaluate}.
 * A missing/blank/oversized/malformed field is rejected with a clean 400 (and the
 * grant action never runs); a valid body still flows through. The 422 reserved-name
 * gate (a separate {@code ResponseStatusException}) is unaffected — covered in
 * {@link RegistrationControllerReservedUsernameTest}.
 */
@WebFluxTest(controllers = RegistrationController.class)
@Import({SecurityConfig.class, net.hugmesh.commons.security.DomainRegistry.class,
        ValidationExceptionHandler.class})
class RegistrationControllerValidationTest {

    @Autowired WebTestClient client;

    @MockitoBean GitHubOrgAdminGrantAction grantAction;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    private WebTestClient asAdmin() {
        return client.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void missingUsername_isRejectedWith400_andGrantActionNeverCalled() {
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("validation failed")
                .jsonPath("$.fields.hugmeshUsername").exists();
        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }

    @Test
    void blankToken_isRejectedWith400() {
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"\",\"hugmeshUsername\":\"magus\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.fields.githubAccessToken").exists();
        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }

    @Test
    void malformedUsername_isRejectedWith400() {
        // A space is not in the allowed username charset.
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"hugmeshUsername\":\"bad name\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.fields.hugmeshUsername").exists();
        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }

    @Test
    void oversizedToken_isRejectedWith400() {
        String big = "a".repeat(300);
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"" + big + "\",\"hugmeshUsername\":\"magus\"}")
                .exchange()
                .expectStatus().isBadRequest();
        verify(grantAction, never()).evaluateAndGrant(any(), any());
    }

    @Test
    void validBody_stillReachesGrantAction() {
        when(grantAction.evaluateAndGrant(eq("gho_x"), eq("magus")))
                .thenReturn(Mono.just("hugmesh/magus"));
        asAdmin().post().uri("/api/registration/github-org-admin/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"githubAccessToken\":\"gho_x\",\"hugmeshUsername\":\"magus\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.memberId").isEqualTo("hugmesh/magus");
        verify(grantAction).evaluateAndGrant("gho_x", "magus");
    }
}
