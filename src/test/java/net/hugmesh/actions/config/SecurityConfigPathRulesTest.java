package net.hugmesh.actions.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Stand-in controller exposing one endpoint per policy tier. Declared
 * top-level so {@code @WebFluxTest(controllers = Probes.class)} registers
 * it as the only handler the slice loads.
 */
@RestController
class Probes {
    @GetMapping("/actuator/health") String health() { return "UP"; }
    @GetMapping("/actuator/info") String info() { return "info"; }
    @GetMapping("/actuator/metrics") String metrics() { return "metrics"; }
    @GetMapping("/api/registration/github-org-admin/evaluate") String reg() { return "ok"; }
    @GetMapping("/api/anything") String anything() { return "ok"; }
}

/**
 * Integration test for the {@link SecurityConfig} path-matcher
 * authorization rules, driven through {@link WebTestClient} with
 * {@code mockJwt()} synthetic authentications.
 *
 * <p>Policy under test:
 *
 * <ul>
 *   <li>{@code /actuator/health} + {@code /actuator/info} — public.</li>
 *   <li>{@code /actuator/**} (other) — {@code ROLE_GAME-MASTER}.</li>
 *   <li>{@code /api/registration/**} — {@code ROLE_ADMIN}.</li>
 *   <li>anything else — merely authenticated.</li>
 * </ul>
 *
 * <p>A throwaway controller stands in for the real endpoints so the test
 * exercises only the security rules, not the grant-action wiring.
 */
@WebFluxTest(controllers = Probes.class)
@Import({SecurityConfig.class, net.hugmesh.commons.security.DomainRegistry.class})
class SecurityConfigPathRulesTest {

    @org.springframework.beans.factory.annotation.Autowired
    WebTestClient client;

    // oauth2ResourceServer needs a decoder bean at context start; mockJwt()
    // bypasses it, so a mock satisfies the wiring without a live JWKS.
    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @Test
    void health_isPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void info_isPublic() {
        client.get().uri("/actuator/info").exchange().expectStatus().isOk();
    }

    @Test
    void metrics_deniedForAnonymous() {
        client.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void metrics_deniedForPlainUser() {
        client.mutateWith(mockJwt().authorities(authorities("ROLE_USER")))
                .get().uri("/actuator/metrics").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void metrics_allowedForGameMaster() {
        client.mutateWith(mockJwt().authorities(authorities("ROLE_USER", "ROLE_GAME-MASTER")))
                .get().uri("/actuator/metrics").exchange()
                .expectStatus().isOk();
    }

    @Test
    void registration_deniedForAnonymous() {
        client.get().uri("/api/registration/github-org-admin/evaluate")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void registration_deniedForGameMasterButNotAdmin() {
        client.mutateWith(mockJwt().authorities(authorities("ROLE_USER", "ROLE_GAME-MASTER")))
                .get().uri("/api/registration/github-org-admin/evaluate")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void registration_allowedForAdmin() {
        client.mutateWith(mockJwt().authorities(authorities("ROLE_USER", "ROLE_ADMIN")))
                .get().uri("/api/registration/github-org-admin/evaluate")
                .exchange().expectStatus().isOk();
    }

    @Test
    void other_deniedForAnonymous() {
        client.get().uri("/api/anything").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void other_allowedForAnyAuthenticatedUser() {
        client.mutateWith(mockJwt().authorities(authorities("ROLE_USER")))
                .get().uri("/api/anything").exchange()
                .expectStatus().isOk();
    }

    private static java.util.Collection<org.springframework.security.core.GrantedAuthority>
            authorities(String... names) {
        java.util.List<org.springframework.security.core.GrantedAuthority> out = new java.util.ArrayList<>();
        for (String n : names) {
            out.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(n));
        }
        return out;
    }
}
