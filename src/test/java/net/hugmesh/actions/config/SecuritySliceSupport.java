package net.hugmesh.actions.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import net.hugmesh.commons.security.DomainRegistry;

/**
 * Shared scaffolding for the hugmesh-actions SECURITY-SLICE tests — builds a
 * {@link WebTestClient} that drives a real controller THROUGH the production
 * {@link SecurityConfig#filterChain} (the actual {@code authorizeExchange} path
 * policy + the OAuth2 resource-server wiring), with NO full Spring Boot context.
 *
 * <p>COPIED from hugmesh-gateway's
 * {@code net.hugmesh.gateway.config.SecuritySliceSupport} (package + the
 * {@link DomainRegistry} import adjusted to this repo). This service's
 * {@link SecurityConfig} constructor — {@code (DomainRegistry, ObjectProvider&lt;...&gt;)}
 * — and its {@code filterChain(ServerHttpSecurity)} signature are byte-for-byte the
 * same as the gateway's, so {@link #sliceClient} needs no other edit.
 *
 * <p>This is the load-bearing half of the copyable multi-issuer pattern (see
 * {@code docs/testing/MULTI-ISSUER-SLICE.md} in the gateway): a resource-server repo
 * replicates the slice by (1) constructing its own {@code SecurityConfig.filterChain}
 * the same way here, (2) supplying issuer-specific tokens via
 * {@link net.hugmesh.actions.testsupport.TestIssuer}, and (3) asserting
 * authority emission / 401 fail-closed against {@code WebTestClient}.
 *
 * <p>NOTE: {@code filterChain(...)} is the PROD chain ({@code @Profile("!dev & !test")}).
 * Calling it directly (no Spring profile is active in a plain slice) is intentional —
 * the slice asserts the production-hardened policy (SPEC_PATHS = ROLE_ADMIN), which is
 * the policy that matters on a deployed instance.
 */
abstract class SecuritySliceSupport {

    /**
     * Build a {@link WebTestClient} bound to {@code controllers}, routed THROUGH the
     * real {@link SecurityConfig#filterChain} built from a fresh
     * {@link ServerHttpSecurity}. The chain is installed as a {@link WebFilterChainProxy}
     * web filter so the resource-server authentication + the {@code authorizeExchange}
     * policy run exactly as in production.
     *
     * @param registry        the issuer-&gt;domain registry (single- or multi-issuer)
     * @param tenantResolver  the OPTIONAL per-issuer resolver provider — pass
     *                        {@link #provider(Object)} with the real resolver to take
     *                        the multi-issuer swap, or {@link #absent()} to stay on
     *                        the single-issuer {@code o.jwt(...)} path
     * @param controllers     the @RestController instances under test
     */
    static WebTestClient sliceClient(
            DomainRegistry registry,
            ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>> tenantResolver,
            Object... controllers) {

        SecurityConfig config = new SecurityConfig(registry, tenantResolver);
        SecurityWebFilterChain chain = config.filterChain(ServerHttpSecurity.http());
        WebFilterChainProxy securityFilter = new WebFilterChainProxy(chain);

        return WebTestClient.bindToController(controllers)
                .webFilter(securityFilter)
                .configureClient()
                .responseTimeout(sliceResponseTimeout())
                .build();
    }

    /**
     * Like {@link #sliceClient} but with {@code app.mfa.enabled} given explicitly, and the
     * chain chosen: {@code prod} builds {@code filterChain}, otherwise {@code devFilterChain}.
     * The step-up rule and its refusal handler are wired on both, so both are exercised.
     */
    static WebTestClient mfaSliceClient(
            DomainRegistry registry,
            ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>> tenantResolver,
            boolean mfaEnabled,
            boolean prod,
            Object... controllers) {

        SecurityConfig config = new SecurityConfig(registry, tenantResolver, mfaEnabled, 600_000L);
        ServerHttpSecurity http = ServerHttpSecurity.http();
        SecurityWebFilterChain chain = prod ? config.filterChain(http) : config.devFilterChain(http);
        return WebTestClient.bindToController(controllers)
                .webFilter(new WebFilterChainProxy(chain))
                .configureClient()
                .responseTimeout(sliceResponseTimeout())
                .build();
    }

    /**
     * The slice's {@link WebTestClient} blocking-read budget. Generous by default (30s)
     * so a cold first-touch OIDC discovery on a constrained CI runner never trips the
     * framework's 5s default; overridable via {@code actions.slice.responseTimeoutSeconds}.
     *
     * <p>FLAKE FIX: the multi-issuer path's FIRST touch of a given brand lazily builds
     * that brand's decoder via {@code ReactiveJwtDecoders.fromIssuerLocation(iss)} — a
     * SYNCHRONOUS OIDC discovery (two sequential HTTP round-trips to the in-process
     * {@link net.hugmesh.actions.testsupport.TestIssuer}, whose JDK HttpServer runs
     * on a single synchronous executor). On a 1-2 core CI runner (cold JVM + cold Netty
     * WebClient bootstrap + JUnit's non-deterministic method ordering deciding which test
     * eats the cold start) it can exceed {@code WebTestClient}'s DEFAULT 5s blocking-read
     * budget, surfacing as a "~5s reactive blocking-read timeout" flake. Pinning an
     * explicit, generous budget makes the slice deterministic WITHOUT touching any
     * assertion: a tampered/unknown/cross-brand token still runs the REAL decoder and
     * still fail-closes 401; we only stop a slow first-touch from being misread as a
     * failure. (Mirrors the fix already in hugmesh-federation-api's SecuritySliceSupport.)
     */
    private static java.time.Duration sliceResponseTimeout() {
        long secs = Long.getLong("actions.slice.responseTimeoutSeconds", 30L);
        if (secs < 5L) secs = 5L; // never go below the framework default
        return java.time.Duration.ofSeconds(secs);
    }

    /** An {@link ObjectProvider} that yields the given value from
     *  {@code getIfAvailable()} (what {@code SecurityConfig} calls). */
    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    /** An empty {@link ObjectProvider} ({@code getIfAvailable()} returns {@code null})
     *  — the single-issuer path, where no tenant resolver was contributed. */
    static <T> ObjectProvider<T> absent() {
        return new ObjectProvider<>() {
            @Override public T getObject() { throw new IllegalStateException("no value"); }
            @Override public T getObject(Object... args) { throw new IllegalStateException("no value"); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }
}
