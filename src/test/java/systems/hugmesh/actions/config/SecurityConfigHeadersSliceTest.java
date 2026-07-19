package systems.hugmesh.actions.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * WebFlux slice test for the reactive HTTP security-header hardening added to
 * {@link SecurityConfig} (OWASP secure-headers / NIST 800-228 / ASVS V14.4).
 *
 * <p>hugmesh-actions is a pure-JSON resource-server effector behind the
 * hugmesh-gateway front door, so the assertions encode the BFF-minimal header
 * contract, NOT the gateway front-door set:
 *
 * <ul>
 *   <li>the API-defensive {@code Content-Security-Policy: default-src 'none';
 *       frame-ancestors 'none'} is present;</li>
 *   <li>the kept Spring secure defaults {@code X-Content-Type-Options: nosniff},
 *       {@code X-Frame-Options: DENY} and an explicit {@code Referrer-Policy:
 *       no-referrer} are present (NOT disabled);</li>
 *   <li><b>no</b> {@code Strict-Transport-Security} is emitted — HSTS lives only at
 *       the TLS-terminating gateway, not on a plaintext intra-mesh BFF hop;</li>
 *   <li>the hardened headers ride alongside the unchanged fail-closed authz floor —
 *       an unauthenticated request to a protected path still 401s.</li>
 * </ul>
 *
 * <p>Reuses the {@link Probes} stand-in controller declared in
 * {@code SecurityConfigPathRulesTest} so the slice exercises only the security
 * filter chain, not the grant-action wiring.
 */
@WebFluxTest(controllers = Probes.class)
@Import({SecurityConfig.class, systems.hugmesh.commons.security.DomainRegistry.class})
class SecurityConfigHeadersSliceTest {

    @org.springframework.beans.factory.annotation.Autowired
    WebTestClient client;

    // oauth2ResourceServer needs a decoder bean at context start; mockJwt() bypasses
    // it, so a mock satisfies the wiring without a live JWKS.
    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @Test
    void publicResponse_carriesHardenedHeaders() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(
                        "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer");
    }

    @Test
    void hsts_isNotEmittedOnTheBff() {
        // HSTS belongs only at the TLS-terminating gateway front door; a plaintext
        // intra-mesh BFF must NOT advertise it.
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Strict-Transport-Security");
    }

    @Test
    void unauthenticatedProtectedRequest_still401s_andStillCarriesHeaders() {
        // The hardening is additive: the deny-by-default floor is unchanged, and the
        // hardened headers are emitted even on the 401 response.
        client.get().uri("/api/anything").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(
                        "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }
}
