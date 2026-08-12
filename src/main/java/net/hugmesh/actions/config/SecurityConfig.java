package net.hugmesh.actions.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.CasdoorAuthorities;
import net.hugmesh.commons.security.DomainRegistry;

/**
 * Reactive Spring Security for hugmesh-actions.
 *
 * <p>Three policy tiers:
 *
 * <ul>
 *   <li>{@code /actuator/health} + {@code /actuator/info} — public
 *       (Kubernetes / Docker probes).</li>
 *   <li>{@code /actuator/**} — restricted to {@code ROLE_GAME-MASTER}.
 *       Same policy hugmesh-gateway (fe/be/pki) /
 *       hugmesh.games BFF enforce so cluster state / metrics /
 *       Casdoor reachability never leak.</li>
 *   <li>{@code /api/registration/**} — restricted to
 *       {@code ROLE_ADMIN}. The action's RegistrationController
 *       expects to be called by an authenticated HugMesh-internal
 *       caller (the registration remote's BFF), NOT directly by a
 *       browser. Internal callers present an admin-scoped Casdoor
 *       token; an external attacker who reaches this endpoint
 *       without one is rejected at the gateway anyway, but
 *       defence-in-depth.</li>
 *   <li>{@code /v3/api-docs/**} + {@code /swagger-ui/**} +
 *       {@code /swagger-ui.html} + {@code /webjars/swagger-ui/**} —
 *       the SpringDoc OpenAPI spec + Swagger-UI. Profile-split, same
 *       as the hugmesh.games BFF: <b>permitAll under dev/test</b>
 *       (so the platform's {@code gen:fetch} can curl the spec locally
 *       without a token) and <b>ROLE_ADMIN otherwise / in prod</b>
 *       (so the spec is never publicly reachable on a deployed
 *       instance).</li>
 *   <li>everything else — authenticated.</li>
 * </ul>
 *
 * <p>The JWT-&gt;authority mapping ({@link #casdoorAuthoritiesConverter}) delegates
 * to the canonical {@link CasdoorAuthorities} (copied from hugmesh-gateway): the
 * Casdoor {@code roles} claim (string or {@code {name}} objects) -&gt;
 * {@code ROLE_<UPPER>}; {@code permissions} verbatim EXCEPT reserved-prefix
 * ({@code ROLE_}/{@code DOMAIN_}) entries which are STRIPPED (authority-injection
 * defense); legacy {@code isAdmin} -&gt; {@code ROLE_ADMIN}; a {@code ROLE_USER}
 * floor; plus exactly ONE issuer-derived {@code DOMAIN_<x>} authority (NON-role;
 * {@code DOMAIN_default} for the single-issuer fallback so this cluster is
 * unchanged). Same authority shape so a single platform-wide role definition flows
 * across every service.
 *
 * <p><b>Multi-issuer is OPT-IN and backward-compatible.</b> The resource server
 * stays on the existing single {@code o.jwt(...)} issuer-uri wiring unless BOTH a
 * per-issuer resolver was contributed ({@link TenancyResourceServerConfig}, gated
 * on {@code app.tenancy.issuers} carrying {@code =} pairs) AND the registry is
 * genuinely multi-issuer ({@link DomainRegistry#isMultiIssuer()}). This service
 * trusts ONLY the hugmesh.systems Casdoor today, so it is byte-for-byte
 * unchanged.
 *
 * <p><b>No per-domain request-scoped data here.</b> The actions service is a
 * single-writer effector: its only request endpoint
 * ({@code POST /api/registration/github-org-admin/evaluate}, ROLE_ADMIN) mutates
 * fixed Casdoor instances + GitHub orgs regardless of the caller's brand, and the
 * sync reconcilers are scheduled (no request principal). It owns NO per-domain
 * row store / query, so the converter + {@code Roles} helpers + the multi-issuer
 * back-compat suffice — there is no domain predicate to add (none is invented).
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    /**
     * SpringDoc OpenAPI + Swagger-UI paths. Gated differently per
     * profile (permitAll in dev/test, ROLE_ADMIN in prod). Kept in one
     * place so the dev and prod chains can't drift on the path list.
     */
    private static final String[] SPEC_PATHS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/webjars/swagger-ui/**",
    };

    /** Issuer-&gt;domain source of truth. Injected so the converter can stamp the
     *  cryptographically-validated {@code iss} as a {@code DOMAIN_<x>} authority.
     *  Single-issuer clusters get a registry that maps the lone issuer (or no issuer
     *  at all) to {@code DOMAIN_default}, so behaviour is unchanged. */
    private final DomainRegistry domainRegistry;

    /** OPTIONAL per-brand multi-issuer resolver. Present (non-null provider value)
     *  ONLY when {@code app.tenancy.issuers} is configured, via
     *  {@link TenancyResourceServerConfig}. When absent the resource server stays on
     *  the single {@code o.jwt(...)} issuer-uri wiring — byte-for-byte the current
     *  single-issuer path. */
    private final ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
            tenantAuthManagerResolver;

    /** D8 MFA floor — derive the {@code MFA_SATISFIED} marker at all. Ships dark:
     *  {@code app.mfa.enabled} defaults to {@code false}, so the converter emits the
     *  legacy byte-identical authority set and the marker is never produced. */
    private final boolean mfaEnabled;

    /** D8 MFA freshness window in millis ({@code app.mfa.freshness-window}, default
     *  10m): a {@code mfa_satisfied_at} stamp older than this (or in the future) does
     *  NOT yield the marker. */
    private final long mfaFreshnessWindowMillis;

    @org.springframework.beans.factory.annotation.Autowired
    public SecurityConfig(DomainRegistry domainRegistry,
                          ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
                                  tenantAuthManagerResolver,
                          @Value("${app.mfa.enabled:false}") boolean mfaEnabled,
                          @Value("${app.mfa.freshness-window:600000}") long mfaFreshnessWindowMillis) {
        this.domainRegistry = domainRegistry;
        this.tenantAuthManagerResolver = tenantAuthManagerResolver;
        this.mfaEnabled = mfaEnabled;
        this.mfaFreshnessWindowMillis = mfaFreshnessWindowMillis;
    }

    /** Legacy convenience (MFA derivation OFF, default 10m window) — keeps the
     *  pre-D8 slice tests compiling and proves the dark default emits the
     *  byte-identical legacy authority set. Production uses the {@code @Autowired}
     *  constructor above. */
    public SecurityConfig(DomainRegistry domainRegistry,
                          ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
                                  tenantAuthManagerResolver) {
        this(domainRegistry, tenantAuthManagerResolver, false, 600_000L);
    }

    /**
     * Shared route rules common to BOTH profiles. The ONLY per-profile difference is
     * how {@link #SPEC_PATHS} is gated, so every other route lives here to keep the
     * two chains in lockstep.
     */
    private static void commonRoutes(ServerHttpSecurity.AuthorizeExchangeSpec ex) {
        ex.pathMatchers("/actuator/health", "/actuator/health/**",
                        "/actuator/info").permitAll();
        ex.pathMatchers("/actuator/**").hasRole("GAME-MASTER");
        ex.pathMatchers("/api/registration/**").hasRole("ADMIN");
    }

    /**
     * Dev / test profile: the OpenAPI spec + Swagger-UI are PUBLIC so the
     * platform's {@code ng-openapi-gen} {@code gen:fetch} step can curl
     * {@code /v3/api-docs} locally without an admin token. Activate with
     * {@code SPRING_PROFILES_ACTIVE=dev} (or {@code test}). Every other
     * rule is identical to the prod chain.
     */
    @Bean
    @Profile({"dev", "test"})
    SecurityWebFilterChain devFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .headers(SecurityConfig::hardenHeaders)
                .authorizeExchange(ex -> {
                    commonRoutes(ex);
                    ex.pathMatchers(SPEC_PATHS).permitAll();
                    ex.anyExchange().authenticated();
                })
                .oauth2ResourceServer(this::configureResourceServer)
                .build();
    }

    /**
     * Production profile (default when dev/test is not active): the
     * OpenAPI spec + Swagger-UI require ROLE_ADMIN so they are never
     * publicly reachable on a deployed instance. No other route is
     * loosened relative to the dev chain.
     */
    @Bean
    @Profile("!dev & !test")
    SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .headers(SecurityConfig::hardenHeaders)
                .authorizeExchange(ex -> {
                    commonRoutes(ex);
                    ex.pathMatchers(SPEC_PATHS).hasRole("ADMIN");
                    ex.anyExchange().authenticated();
                })
                .oauth2ResourceServer(this::configureResourceServer)
                .build();
    }

    /**
     * Reactive HTTP security-header hardening (OWASP secure-headers / NIST 800-228 /
     * ASVS V14.4). hugmesh-actions is a pure-JSON resource-server effector that
     * sits BEHIND the hugmesh-gateway front door and never serves an HTML document
     * — so it gets the BFF-minimal, API-defensive header set, NOT the full
     * browser-policy set:
     *
     * <ul>
     *   <li><b>Content-Security-Policy: {@code default-src 'none'; frame-ancestors
     *       'none'}</b> — defence-in-depth for a no-document API: a sniffed/embedded
     *       response loads nothing and cannot be framed.</li>
     *   <li><b>NO HSTS</b> — Strict-Transport-Security is meaningless over the
     *       plaintext intra-mesh hop and belongs only at the single TLS-terminating
     *       gateway front door (emitting it here would be misleading). hugmesh-actions
     *       is NOT the gateway, so the gateway-only extras (HSTS, Permissions-Policy,
     *       COOP/CORP, the UI-shell CSP) are deliberately omitted.</li>
     *   <li><b>Spring secure defaults KEPT</b> — {@code X-Content-Type-Options: nosniff},
     *       {@code X-Frame-Options: DENY}, {@code Cache-Control: no-store} and
     *       {@code Referrer-Policy} are reactive Spring Security defaults and are NOT
     *       disabled. Referrer-Policy is set explicitly to {@code no-referrer} so the
     *       posture is pinned and cannot silently drift.</li>
     * </ul>
     *
     * <p>This is purely ADDITIVE: it adds response headers and keeps every Spring
     * default. It does NOT touch authz rules, issuer validation, or the
     * {@code csrf.disable()} posture (correct-by-design for a stateless bearer
     * resource server — bearer tokens are not ambient like cookies, so there is no
     * CSRF vector).
     */
    private static void hardenHeaders(ServerHttpSecurity.HeaderSpec h) {
        h.contentSecurityPolicy(csp ->
                csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"));
        h.referrerPolicy(rp -> rp.policy(
                org.springframework.security.web.server.header
                        .ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER));
        // X-Content-Type-Options (nosniff), X-Frame-Options (DENY) and Cache-Control
        // are reactive Spring Security secure defaults — left implicit, NOT disabled.
    }

    /**
     * Resource-server wiring shared by both chains. Multi-issuer is OPT-IN: swap to
     * the per-issuer authentication-manager resolver ONLY when BOTH a resolver was
     * contributed by {@link TenancyResourceServerConfig} AND the registry is
     * genuinely multi-issuer (more than the single back-compat entry). Gating on
     * {@code isMultiIssuer()} — not merely {@code resolver != null} — keeps a
     * single-issuer cluster on the EXISTING issuer-uri {@code o.jwt(...)} path even
     * if the tenancy conditional ever activates with just the lone back-compat
     * issuer. In true multi-brand mode each trusted per-brand Casdoor validates its
     * own tokens (unknown iss -> 401, fail-closed).
     */
    private void configureResourceServer(ServerHttpSecurity.OAuth2ResourceServerSpec o) {
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                tenantAuthManagerResolver.getIfAvailable();
        if (resolver != null && domainRegistry.isMultiIssuer()) {
            o.authenticationManagerResolver(resolver);
        } else {
            o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter()));
        }
    }

    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(casdoorAuthoritiesConverter());
        return converter;
    }

    Converter<Jwt, Flux<GrantedAuthority>> casdoorAuthoritiesConverter() {
        return CasdoorAuthorities.converter(domainRegistry,
                new CasdoorAuthorities.MfaConfig(mfaEnabled, mfaFreshnessWindowMillis));
    }
}
