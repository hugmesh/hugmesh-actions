package net.hugmesh.actions.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import net.hugmesh.actions.testsupport.TestIssuer;
import net.hugmesh.commons.config.TenancyResolvers;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.DomainRegistry;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-issuer security-slice for hugmesh-actions — the org-wide pattern proven
 * first in hugmesh-gateway (see {@code docs/testing/MULTI-ISSUER-SLICE.md}),
 * replicated here so this resource server carries the same fail-closed guarantee.
 *
 * <p>Drives a {@link WebTestClient} against the REAL
 * {@link SecurityConfig#filterChain} wired with the REAL
 * {@link TenancyResourceServerConfig#tenantAuthManagerResolver()} (multi-issuer
 * ENABLED). Each brand is backed by a {@link TestIssuer} — an in-process JWKS +
 * OIDC-discovery server on {@code 127.0.0.1} — so {@code fromIssuerLocation}
 * validates real RSA-signed tokens with NO network and NO live Casdoor.
 *
 * <p>The three keystones:
 * <ol>
 *   <li><b>(a) trusted issuer resolves + authority emitted</b> — a brand-X token
 *       resolves to brand-X's manager, authenticates, and the {@code DOMAIN_brandx}
 *       authority is emitted (the per-issuer Casdoor converter is wired);</li>
 *   <li><b>(b) UNKNOWN issuer -> 401 fail-closed</b> — a token from an issuer NOT in
 *       {@code app.tenancy.issuers} gets NO manager (resolver returns empty) and
 *       401s, NEVER a default-accept;</li>
 *   <li><b>(c) cross-brand isolation</b> — a brand-Y token can NEVER become brand-X
 *       (it carries brand-Y's {@code iss}, validates only against brand-Y's JWKS).</li>
 * </ol>
 *
 * <p>Categories per the comprehensive test standard: (a) is USER-SITUATIONAL (a
 * brand's own token on its own surface); (b)+(c) are CROSS-USER DENY (the
 * tenant-isolation boundary — an unknown / wrong-brand issuer can never cross in).
 *
 * <p>The probe path {@code /whoami} is an ungoverned (non-public, non-infra) path, so
 * it falls to {@code anyExchange().authenticated()} — exactly the surface that proves
 * "did the token authenticate at all, and as which brand", isolated from the coarser
 * role gates (those are covered by {@link SecurityConfigPathGateSliceTest}).
 */
class MultiIssuerSecuritySliceTest extends SecuritySliceSupport {

    private TestIssuer brandX;
    private TestIssuer brandY;
    private TestIssuer rogue;   // NOT trusted
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        brandX = new TestIssuer();
        brandY = new TestIssuer();
        rogue = new TestIssuer();

        // Multi-issuer registry: brand-X + brand-Y trusted; rogue is NOT listed.
        String issuersCsv = brandX.issuerUri() + "=brandx," + brandY.issuerUri() + "=brandy";
        DomainRegistry registry = new DomainRegistry(brandX.issuerUri(), issuersCsv);
        assertThat(registry.isMultiIssuer())
                .as("two trusted issuers => multi-issuer mode, the resolver swap is taken")
                .isTrue();

        // REAL resolver bean (per-issuer manager + Casdoor converter), built exactly
        // as TenancyResourceServerConfig contributes it. No back-compat jwk-set-uri,
        // so each brand decoder discovers from its own issuer (the in-process server).
        // MFA derivation OFF (false, default 10m window) — matches the dark default.
        TenancyResourceServerConfig tenancy =
                new TenancyResourceServerConfig(registry, "", "", false, 600_000L);
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                TenancyResolvers.tenantResolver(tenancy);

        client = sliceClient(registry, provider(resolver), new AuthoritiesController());
    }

    @AfterEach
    void tearDown() {
        brandX.close();
        brandY.close();
        rogue.close();
    }

    // ── (a) trusted issuer resolves + DOMAIN_<x> authority emitted ────────────

    @Test
    void userSituational_brandXToken_authenticates_andEmitsDomainXAuthority() {
        String token = brandX.mint("user-x", List.of("admin"));
        client.get().uri("/whoami")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("brand-X token resolves to brand-X's manager and is stamped DOMAIN_brandx")
                        .contains("DOMAIN_brandx")
                        // the Casdoor converter ran: roles -> ROLE_, floor ROLE_USER
                        .contains("ROLE_ADMIN")
                        .contains("ROLE_USER")
                        // and NOT the other brand's domain
                        .doesNotContain("DOMAIN_brandy"));
    }

    @Test
    void multiUser_brandYToken_authenticates_andEmitsDomainYAuthority() {
        String token = brandY.mint("user-y", List.of("game-master"));
        client.get().uri("/whoami")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("DOMAIN_brandy")
                        // role-name -> ROLE_<UPPER> (hyphen preserved, see CasdoorAuthorities)
                        .contains("ROLE_GAME-MASTER")
                        .doesNotContain("DOMAIN_brandx"));
    }

    // ── (b) UNKNOWN issuer -> 401 fail-closed ─────────────────────────────────

    @Test
    void crossUser_unknownIssuerToken_is401_failClosed_neverDefaultAccept() {
        // The rogue issuer is a perfectly valid, well-formed, correctly-signed JWT —
        // it is just NOT in app.tenancy.issuers. The per-issuer resolver returns
        // Mono.empty() for it, which the JwtIssuerReactiveAuthenticationManagerResolver
        // surfaces as an authentication failure: 401. There is NO default manager that
        // would let an unlisted issuer through.
        String token = rogue.mint("attacker", List.of("admin", "superadmin"));
        client.get().uri("/whoami")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void crossUser_garbageBearer_is401() {
        // A non-JWT bearer also fails closed (no issuer to resolve).
        client.get().uri("/whoami")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── (c) cross-brand isolation — brand-Y token can never become brand-X ────

    @Test
    void crossUser_brandYToken_cannotImpersonateBrandX() {
        // A brand-Y-issued token carries brand-Y's `iss` and is signed by brand-Y's
        // key. It validates ONLY against brand-Y's manager. It can NEVER authenticate
        // AS brand-X: there is no path by which presenting a brand-Y token yields a
        // DOMAIN_brandx authority. We prove the positive (it authenticates as brand-Y,
        // never brand-X) — the isolation boundary holds in both directions.
        String token = brandY.mint("user-y", List.of("admin"));
        client.get().uri("/whoami")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("a brand-Y token is scoped to brand-Y; it can NEVER become brand-X")
                        .contains("DOMAIN_brandy")
                        .doesNotContain("DOMAIN_brandx"));
    }

    @Test
    void crossUser_permissionsClaim_cannotForgeCrossBrandDomainAuthority() {
        // SECURITY: a brand-Y token whose `permissions` claim tries to inject
        // DOMAIN_brandx (and ROLE_SUPERADMIN) must NOT escalate cross-tenant. The
        // CasdoorAuthorities reserved-prefix filter strips ROLE_/DOMAIN_ permission
        // entries; the ONLY DOMAIN_ authority is the cryptographically-validated iss.
        String token = brandY.mintWithClaims("attacker-y", java.util.Map.of(
                "roles", List.of(java.util.Map.of("name", "user")),
                "permissions", List.of("DOMAIN_brandx", "ROLE_SUPERADMIN", "reports:read")));
        client.get().uri("/whoami")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("forged DOMAIN_/ROLE_ permission entries are stripped; iss-derived DOMAIN wins")
                        .contains("DOMAIN_brandy")
                        .doesNotContain("DOMAIN_brandx")
                        .doesNotContain("ROLE_SUPERADMIN")
                        // a non-reserved permission still flows through verbatim
                        .contains("reports:read"));
    }

    @Test
    void crossUser_tamperedSignature_is401() {
        // A brand-X token whose signature is corrupted fails brand-X's manager
        // validation -> 401 (not silently accepted). Same fail-closed posture.
        String token = brandX.mint("user-x", List.of("admin"));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        client.get().uri("/whoami")
                .header("Authorization", "Bearer " + tampered)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Echoes the authenticated principal's authorities — the simplest probe that
     *  proves which manager resolved the token and which authorities it emitted. */
    @RestController
    static class AuthoritiesController {
        @GetMapping("/whoami")
        Mono<String> whoami() {
            return ReactiveSecurityContextHolder.getContext()
                    .map(ctx -> ctx.getAuthentication().getAuthorities().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(",")))
                    .defaultIfEmpty("ANONYMOUS");
        }
    }

    /** Wrap a resolver instance as an ObjectProvider (what SecurityConfig injects).
     *  Kept for symmetry with the gateway reference (which named it {@code present}). */
    @SuppressWarnings("unused")
    private static ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>> present(
            ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver) {
        return SecuritySliceSupport.provider(resolver);
    }
}
