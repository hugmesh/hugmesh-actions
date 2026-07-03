package systems.snapdragon.actions.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.testsupport.TestIssuer;
import systems.snapdragon.commons.config.TenancyResolvers;
import systems.snapdragon.commons.config.TenancyResourceServerConfig;
import systems.snapdragon.commons.security.DomainRegistry;

import java.util.List;

/**
 * Coarse PATH-GATE slice — the snapdragon-actions {@link SecurityConfig}
 * {@code authorizeExchange} policy, end-to-end through the real filter chain.
 *
 * <p>Complements {@code SecurityConfigPathRulesTest} (a {@code @WebFluxTest} +
 * {@code mockJwt()} slice that bypasses the decoder): this slice drives REAL
 * RSA-signed multi-issuer tokens through the production resolver path (the only
 * no-network decoder seam, backed by an in-process {@link TestIssuer}), so the
 * authorization gating is proven on top of the SAME authentication wiring the
 * multi-issuer slice exercises. The {@code authorizeExchange} rules are
 * auth-decoder-agnostic, so the gates asserted here are identical on the
 * single-issuer path.
 *
 * <p>The actions PROD policy ({@code @Profile("!dev & !test")} chain — the one
 * {@code sliceClient} builds):
 * <ul>
 *   <li>{@code /actuator/health}, {@code /actuator/health/**}, {@code /actuator/info}
 *       — PUBLIC (k8s/docker probes);</li>
 *   <li>{@code /actuator/**} (other) — {@code ROLE_GAME-MASTER};</li>
 *   <li>{@code /api/registration/**} — {@code ROLE_ADMIN};</li>
 *   <li>SPEC_PATHS ({@code /v3/api-docs/**}, {@code /swagger-ui/**},
 *       {@code /swagger-ui.html}, {@code /webjars/swagger-ui/**}) — {@code ROLE_ADMIN}
 *       in prod (NEVER publicly reachable on a deployed instance);</li>
 *   <li>everything else — merely {@code authenticated()}.</li>
 * </ul>
 *
 * <p>Asserts ANONYMOUS allow/deny and authenticated role allow/deny across each tier
 * (USER-SITUATIONAL ALLOW + CROSS-USER DENY per the comprehensive test standard).
 */
class SecurityConfigPathGateSliceTest extends SecuritySliceSupport {

    private TestIssuer issuer;
    private TestIssuer issuer2;   // a second trusted issuer so isMultiIssuer() holds
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer();
        issuer2 = new TestIssuer();
        String issuersCsv = issuer.issuerUri() + "=brandx," + issuer2.issuerUri() + "=brandy";
        DomainRegistry registry = new DomainRegistry(issuer.issuerUri(), issuersCsv);
        TenancyResourceServerConfig tenancy =
                new TenancyResourceServerConfig(registry, "", "", false, 600_000L);
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                TenancyResolvers.tenantResolver(tenancy);
        client = sliceClient(registry, provider(resolver), new ProbeController());
    }

    @AfterEach
    void tearDown() {
        issuer.close();
        issuer2.close();
    }

    private WebTestClient.RequestHeadersSpec<?> as(List<String> roles, String path) {
        String token = issuer.mint("p-" + String.join("-", roles), roles);
        return client.get().uri(path).header("Authorization", "Bearer " + token);
    }

    // ── PUBLIC — health + info reachable anonymously ──────────────────────────

    @Test
    void anonymous_allowedOn_health() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void anonymous_allowedOn_healthSubpath() {
        // /actuator/health/** is explicitly permitAll (liveness/readiness probes).
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void anonymous_allowedOn_info() {
        client.get().uri("/actuator/info").exchange().expectStatus().isOk();
    }

    // ── /actuator/** (other) — ROLE_GAME-MASTER ───────────────────────────────

    @Test
    void anonymous_deniedOn_actuatorMetrics() {
        // Not public — anonymous gets 401 (no auth) at the gate.
        client.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void user_forbiddenOn_actuatorMetrics() {
        // Authenticated but lacking ROLE_GAME-MASTER -> 403, never 200. Cluster
        // state / metrics / Casdoor reachability never leak to a plain user.
        as(List.of("user"), "/actuator/metrics").exchange().expectStatus().isForbidden();
    }

    @Test
    void admin_forbiddenOn_actuatorMetrics() {
        // SECURITY NOTE: /actuator/** is GAME-MASTER ONLY — ROLE_ADMIN is NOT in the
        // allowlist and there is no role hierarchy in this service, so an admin is
        // 403 on actuator. (Distinct from the gateway, where ADMIN reaches infra.)
        as(List.of("admin"), "/actuator/metrics").exchange().expectStatus().isForbidden();
    }

    @Test
    void gameMaster_allowedOn_actuatorMetrics() {
        as(List.of("game-master"), "/actuator/metrics").exchange().expectStatus().isOk();
    }

    // ── /api/registration/** — ROLE_ADMIN ─────────────────────────────────────

    @Test
    void anonymous_deniedOn_registration() {
        client.get().uri("/api/registration/github-org-admin/evaluate")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void user_forbiddenOn_registration() {
        as(List.of("user"), "/api/registration/github-org-admin/evaluate")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void gameMaster_forbiddenOn_registration() {
        // Cross-role DENY: GAME-MASTER is the infra role, NOT the registration role.
        as(List.of("game-master"), "/api/registration/github-org-admin/evaluate")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void admin_allowedOn_registration() {
        as(List.of("admin"), "/api/registration/github-org-admin/evaluate")
                .exchange().expectStatus().isOk();
    }

    // ── SPEC_PATHS — ROLE_ADMIN in the PROD chain ─────────────────────────────

    @Test
    void anonymous_deniedOn_apiDocs_inProdChain() {
        // The prod filterChain gates the OpenAPI spec behind ROLE_ADMIN so it is
        // NEVER publicly reachable on a deployed instance. (Dev/test permitAll is a
        // separate chain not built by this slice.)
        client.get().uri("/v3/api-docs").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void user_forbiddenOn_apiDocs_inProdChain() {
        as(List.of("user"), "/v3/api-docs").exchange().expectStatus().isForbidden();
    }

    @Test
    void admin_allowedOn_apiDocs_inProdChain() {
        as(List.of("admin"), "/v3/api-docs").exchange().expectStatus().isOk();
    }

    // ── anyExchange().authenticated() floor ───────────────────────────────────

    @Test
    void anonymous_deniedOn_arbitraryGatedPath() {
        // An ungoverned, non-public path 401s an anonymous caller.
        client.get().uri("/api/anything").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void user_allowedOn_arbitraryGatedPath() {
        // Any authenticated principal (floor ROLE_USER) reaches a merely-authenticated
        // path — proving the floor is authenticate, not a blanket role deny.
        as(List.of("user"), "/api/anything").exchange().expectStatus().isOk();
    }

    /** Maps every path the slice exercises so an ALLOWED request resolves to 200
     *  (instead of a 404 that could mask a missing security decision). */
    @RestController
    static class ProbeController {
        @RequestMapping({
                "/actuator/health", "/actuator/health/readiness", "/actuator/info",
                "/actuator/metrics",
                "/api/registration/github-org-admin/evaluate",
                "/v3/api-docs",
                "/api/anything",
        })
        Mono<String> ok() {
            return Mono.just("ok");
        }
    }
}
