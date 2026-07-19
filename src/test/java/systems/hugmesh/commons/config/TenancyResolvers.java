package systems.hugmesh.commons.config;

import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.web.server.ServerWebExchange;

/**
 * Same-package TEST accessor for the package-private
 * {@link TenancyResourceServerConfig#tenantAuthManagerResolver()} {@code @Bean}.
 *
 * <p>WHY THIS EXISTS (recipe note, copied verbatim from hugmesh-federation-api's
 * commons-adoption pattern). {@code TenancyResourceServerConfig} lives in
 * {@code systems.hugmesh.commons.config} and its {@code tenantAuthManagerResolver()}
 * bean method stays <b>package-private by design</b> (only Spring should publish it).
 * A security-slice test in this repo's own {@code systems.hugmesh.actions.config}
 * package can no longer reach it directly after the commons lift.
 *
 * <p>This tiny accessor — placed in {@code systems.hugmesh.commons.config} under
 * THIS repo's <b>test</b> sources — re-opens exactly that one method to the slice
 * tests, with no change to the shipped commons jar and no widening of the production
 * API surface.
 */
public final class TenancyResolvers {

    private TenancyResolvers() {}

    /** Build the multi-issuer tenant {@link ReactiveAuthenticationManagerResolver}
     *  from a configured {@link TenancyResourceServerConfig} — the exact resolver the
     *  production {@code @Bean} publishes, for use in a security slice without a
     *  Spring context. */
    public static ReactiveAuthenticationManagerResolver<ServerWebExchange> tenantResolver(
            TenancyResourceServerConfig config) {
        return config.tenantAuthManagerResolver();
    }
}
