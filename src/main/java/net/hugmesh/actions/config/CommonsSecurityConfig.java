package net.hugmesh.actions.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.DomainRegistry;

/**
 * Registers the shared {@code net.hugmesh.commons} security/tenancy beans into
 * this service's application context.
 *
 * <p><b>Why this exists (recipe note, per the commons adoption doc).</b> When
 * {@link DomainRegistry} (an {@code @Component}) and {@link TenancyResourceServerConfig}
 * (an {@code @Configuration} gated by {@code @ConditionalOnExpression}) lived inside
 * this repo's own {@code net.hugmesh.actions.security} / {@code .config}
 * packages, the {@code @SpringBootApplication} component scan over
 * {@code net.hugmesh.actions} picked them up automatically. After the lift to
 * {@code net.hugmesh.commons.*} (outside that scan), {@link SecurityConfig}'s
 * {@link DomainRegistry} dependency would go unsatisfied and the application would
 * fail to start.
 *
 * <p>Rather than widen {@code scanBasePackages} to the whole commons tree (which would
 * auto-adopt every future commons {@code @Component} implicitly), this
 * {@code @Import}s exactly the two commons beans this service needs — same pattern as
 * {@code hugmesh-federation-api}'s {@code CommonsSecurityConfig}:
 * <ul>
 *   <li>{@link DomainRegistry} — the issuer->domain registry consumed by
 *       {@link SecurityConfig} and the Casdoor authorities converter. Its
 *       {@code @Value} constructor wiring ({@code spring.security.oauth2.resourceserver
 *       .jwt.issuer-uri} + {@code app.tenancy.issuers}) is honoured by {@code @Import}
 *       exactly as a component scan would.</li>
 *   <li>{@link TenancyResourceServerConfig} — the CONFIG-GATED multi-issuer wiring.
 *       Importing the {@code @Configuration} class PRESERVES its
 *       {@code @ConditionalOnExpression("'${app.tenancy.issuers:}'.contains('=')")}
 *       guard, so this single-issuer cluster stays byte-for-byte on the existing
 *       single-issuer path and the {@code tenantAuthManagerResolver} {@code @Bean} is
 *       contributed ONLY when real {@code issuer=domain} pairs are configured.</li>
 * </ul>
 */
@Configuration
@Import({DomainRegistry.class, TenancyResourceServerConfig.class})
public class CommonsSecurityConfig {
}
