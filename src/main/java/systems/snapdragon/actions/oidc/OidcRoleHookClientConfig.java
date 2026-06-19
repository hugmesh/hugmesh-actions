package systems.snapdragon.actions.oidc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.federation.FederationUsernameClientConfig;

import java.util.function.Supplier;

/**
 * Wires the {@link OidcRoleHookClient} the OIDC default-role registration
 * probe calls — a ROLE_SERVICE WebClient against snapdragon-admin-bff-api's
 * {@code POST /bff/internal/oidc/first-login} endpoint.
 *
 * <p><b>GATED, DEFAULT OFF.</b> The bean is only created when
 * {@code app.actions.oidc-default-roles.enabled=true} — identical gating to
 * {@link FederationUsernameClientConfig}. Local / dev / CI (and every existing
 * test) leave it off, so the {@code OidcDefaultRoleProbeAction} injects the
 * client via an {@code ObjectProvider} and short-circuits when it is absent —
 * defence in depth on top of the action's own {@code enabled} flag. In-cluster
 * the deployment flips {@code APP_OIDC_DEFAULT_ROLES_ENABLED=true} to opt in.
 *
 * <p><b>Service bearer.</b> The first-login endpoint is INTERNAL / ROLE_SERVICE
 * gated, so this caller presents a Casdoor SERVICE JWT minted via the OAuth2
 * client-credentials grant against snapdragon.systems Casdoor — reusing the
 * exact same {@code app.casdoor.systems.{base-url,client-id,client-secret}}
 * credentials the service already holds (and the very same cached-token supplier
 * as the federation client, via
 * {@link FederationUsernameClientConfig#serviceTokenSupplier}).
 *
 * <p><b>Base URL</b> defaults to the in-cluster DNS of admin-bff-api,
 * env-overridable via {@code APP_OIDC_DEFAULT_ROLES_ADMIN_BFF_BASE_URL}.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.actions.oidc-default-roles", name = "enabled", havingValue = "true")
public class OidcRoleHookClientConfig {

    @Bean
    public OidcRoleHookClient oidcRoleHookClient(
            @Value("${app.actions.oidc-default-roles.admin-bff-base-url:http://snapdragon-admin-bff-api.snapdragon-backend.svc.cluster.local:8080}")
            String adminBffBaseUrl,
            @Value("${app.casdoor.systems.base-url}") String casdoorBaseUrl,
            @Value("${app.casdoor.systems.client-id}") String clientId,
            @Value("${app.casdoor.systems.client-secret}") String clientSecret) {

        WebClient adminBff = WebClient.builder().baseUrl(adminBffBaseUrl).build();
        WebClient casdoor  = WebClient.builder().baseUrl(casdoorBaseUrl).build();

        Supplier<Mono<String>> tokenSupplier =
                FederationUsernameClientConfig.serviceTokenSupplier(casdoor, clientId, clientSecret);

        return new OidcRoleHookClient(adminBff, tokenSupplier);
    }
}
