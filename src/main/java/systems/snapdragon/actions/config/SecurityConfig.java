package systems.snapdragon.actions.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reactive Spring Security for snapdragon-actions.
 *
 * <p>Three policy tiers:
 *
 * <ul>
 *   <li>{@code /actuator/health} + {@code /actuator/info} — public
 *       (Kubernetes / Docker probes).</li>
 *   <li>{@code /actuator/**} — restricted to {@code ROLE_GAME-MASTER}.
 *       Same policy snapdragon-gateway / snapdragon-id-gateway /
 *       snapdragon.games BFF enforce so cluster state / metrics /
 *       Casdoor reachability never leak.</li>
 *   <li>{@code /api/registration/**} — restricted to
 *       {@code ROLE_ADMIN}. The action's RegistrationController
 *       expects to be called by an authenticated Snapdragon-internal
 *       caller (the registration remote's BFF), NOT directly by a
 *       browser. Internal callers present an admin-scoped Casdoor
 *       token; an external attacker who reaches this endpoint
 *       without one is rejected at the gateway anyway, but
 *       defence-in-depth.</li>
 *   <li>{@code /v3/api-docs/**} + {@code /swagger-ui/**} +
 *       {@code /swagger-ui.html} + {@code /webjars/swagger-ui/**} —
 *       the SpringDoc OpenAPI spec + Swagger-UI. Profile-split, same
 *       as the snapdragon.games BFF: <b>permitAll under dev/test</b>
 *       (so the platform's {@code gen:fetch} can curl the spec locally
 *       without a token) and <b>ROLE_ADMIN otherwise / in prod</b>
 *       (so the spec is never publicly reachable on a deployed
 *       instance).</li>
 *   <li>everything else — authenticated.</li>
 * </ul>
 *
 * <p>JWT decoder + authorities converter mirrors the snapdragon.games
 * BFF + snapdragon-gateway pattern: same Casdoor instance, same
 * authority shape so a single platform-wide role definition flows
 * across every service.
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
                .authorizeExchange(ex -> {
                    ex.pathMatchers("/actuator/health", "/actuator/health/**",
                                    "/actuator/info").permitAll();
                    ex.pathMatchers("/actuator/**").hasRole("GAME-MASTER");
                    ex.pathMatchers(SPEC_PATHS).permitAll();
                    ex.pathMatchers("/api/registration/**").hasRole("ADMIN");
                    ex.anyExchange().authenticated();
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())))
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
                .authorizeExchange(ex -> {
                    ex.pathMatchers("/actuator/health", "/actuator/health/**",
                                    "/actuator/info").permitAll();
                    ex.pathMatchers("/actuator/**").hasRole("GAME-MASTER");
                    ex.pathMatchers(SPEC_PATHS).hasRole("ADMIN");
                    ex.pathMatchers("/api/registration/**").hasRole("ADMIN");
                    ex.anyExchange().authenticated();
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())))
                .build();
    }

    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(casdoorAuthoritiesConverter());
        return converter;
    }

    /**
     * Casdoor claim -> Spring authority mapping. Package-private + static
     * so it can be unit-tested directly against synthetic {@link Jwt}s:
     *
     * <ul>
     *   <li>every authenticated principal gets a {@code ROLE_USER} floor;</li>
     *   <li>each {@code roles} entry (a string, or an object with a
     *       {@code name}) becomes {@code ROLE_<UPPERCASE>};</li>
     *   <li>each {@code permissions} entry becomes a verbatim authority;</li>
     *   <li>{@code isAdmin == true} adds {@code ROLE_ADMIN}.</li>
     * </ul>
     */
    static Converter<Jwt, Flux<GrantedAuthority>> casdoorAuthoritiesConverter() {
        return jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

            Object rolesClaim = jwt.getClaim("roles");
            if (rolesClaim instanceof Collection<?> list) {
                for (Object r : list) {
                    String name = roleNameOf(r);
                    if (!name.isBlank()) {
                        authorities.add(new SimpleGrantedAuthority(
                                "ROLE_" + name.trim().toUpperCase()));
                    }
                }
            }

            Object permsClaim = jwt.getClaim("permissions");
            if (permsClaim instanceof Collection<?> list) {
                for (Object p : list) {
                    if (p != null) {
                        authorities.add(new SimpleGrantedAuthority(p.toString().trim()));
                    }
                }
            }

            if (Boolean.TRUE.equals(jwt.getClaim("isAdmin"))) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }

            return Flux.fromIterable(authorities);
        };
    }

    private static String roleNameOf(Object item) {
        if (item instanceof String s) return s;
        if (item instanceof Map<?, ?> m) {
            Object name = m.get("name");
            if (name instanceof String s && !s.isBlank()) return s;
        }
        return "";
    }
}
