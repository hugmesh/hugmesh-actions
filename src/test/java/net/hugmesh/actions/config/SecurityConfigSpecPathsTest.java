package net.hugmesh.actions.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Verifies the SpringDoc OpenAPI spec / Swagger-UI path gating in
 * {@link SecurityConfig}, which is profile-split exactly like the
 * hugmesh.games BFF:
 *
 * <ul>
 *   <li><b>dev / test profile</b> — the spec paths are PUBLIC so the
 *       platform's {@code ng-openapi-gen} {@code gen:fetch} can curl
 *       {@code /v3/api-docs} locally without a token.</li>
 *   <li><b>prod (no dev/test profile)</b> — the spec paths require
 *       {@code ROLE_ADMIN} so the spec is never publicly reachable.</li>
 * </ul>
 *
 * <p>A throwaway controller stands in for the SpringDoc-served handlers
 * so the test exercises only the security path-matcher, not the live
 * spec generation (which the WebFlux slice doesn't wire up anyway).
 */
class SecurityConfigSpecPathsTest {

    /** Stands in for the SpringDoc-served spec + UI endpoints. */
    @RestController
    static class SpecProbes {
        @GetMapping("/v3/api-docs") String apiDocs() { return "{}"; }
        @GetMapping("/swagger-ui.html") String ui() { return "<html/>"; }
    }

    static java.util.Collection<org.springframework.security.core.GrantedAuthority>
            authorities(String... names) {
        java.util.List<org.springframework.security.core.GrantedAuthority> out = new java.util.ArrayList<>();
        for (String n : names) {
            out.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(n));
        }
        return out;
    }

    @Nested
    @WebFluxTest(controllers = SpecProbes.class)
    @Import({SecurityConfig.class, net.hugmesh.commons.security.DomainRegistry.class})
    @ActiveProfiles("dev")
    class DevProfile {

        @org.springframework.beans.factory.annotation.Autowired
        WebTestClient client;

        @org.springframework.test.context.bean.override.mockito.MockitoBean
        ReactiveJwtDecoder devDecoder;

        @Test
        void apiDocs_isPublicInDev() {
            client.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
        }

        @Test
        void swaggerUi_isPublicInDev() {
            client.get().uri("/swagger-ui.html").exchange().expectStatus().isOk();
        }
    }

    @Nested
    @WebFluxTest(controllers = SpecProbes.class)
    @Import({SecurityConfig.class, net.hugmesh.commons.security.DomainRegistry.class})
    class ProdProfile {

        @org.springframework.beans.factory.annotation.Autowired
        WebTestClient client;

        @org.springframework.test.context.bean.override.mockito.MockitoBean
        ReactiveJwtDecoder prodDecoder;

        @Test
        void apiDocs_deniedForAnonymousInProd() {
            client.get().uri("/v3/api-docs").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void apiDocs_deniedForPlainUserInProd() {
            client.mutateWith(mockJwt().authorities(authorities("ROLE_USER")))
                    .get().uri("/v3/api-docs").exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        void apiDocs_allowedForAdminInProd() {
            client.mutateWith(mockJwt().authorities(authorities("ROLE_USER", "ROLE_ADMIN")))
                    .get().uri("/v3/api-docs").exchange()
                    .expectStatus().isOk();
        }
    }
}
