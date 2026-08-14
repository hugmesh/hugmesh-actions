package net.hugmesh.actions.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import net.hugmesh.actions.federation.FederationUsernameClientConfig;

/**
 * Wires the {@link UserNotificationClient} the reconcilers' notification
 * writer ({@link RoleChangeNotifier}) uses to deliver in-app notifications
 * through user-bff's SERVICE-only internal endpoint.
 *
 * <p><b>GATED, DEFAULT OFF (ships dark).</b> The bean is only created when
 * {@code app.actions.notify.enabled=true} ({@code ACTIONS_NOTIFY_ENABLED}) —
 * the same shape as the platform's other internal-call gates (federation
 * username check, OIDC role hook). Local / dev / CI and every existing test
 * leave it off, so {@link RoleChangeNotifier} is a logged no-op and the
 * reconcilers behave exactly as before. In-cluster the deployment flips the
 * flag once user-bff (with the notifications floor + SSE bus, both already on
 * user-bff main) is rolled out; only then does the deliver path go live.
 * HONESTY NOTE: while dark, NOTHING writes {@code user_notifications} in
 * normal operation — this is disclosed in both repos' READMEs rather than
 * being silently "on".
 *
 * <p><b>Service bearer.</b> The deliver endpoint is INTERNAL / ROLE_SERVICE
 * gated, so this caller presents a Casdoor SERVICE JWT minted via the OAuth2
 * client-credentials grant against hugmesh.net Casdoor — reusing the
 * canonical {@link FederationUsernameClientConfig#serviceTokenSupplier}
 * mint+cache and the same {@code app.casdoor.platform.*} credentials every
 * other internal ROLE_SERVICE caller in this service uses.
 *
 * <p><b>Base URL</b> defaults to the in-cluster DNS of user-bff-api,
 * env-overridable via {@code USER_BFF_API_BASE_URL}.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.actions.notify", name = "enabled", havingValue = "true")
public class UserNotificationClientConfig {

    @Bean
    public UserNotificationClient userNotificationClient(
            @Value("${app.actions.notify.user-bff-base-url:http://hugmesh-user-bff-api.hugmesh-backend.svc.cluster.local:8080}")
            String baseUrl,
            @Value("${app.casdoor.platform.base-url}") String casdoorBaseUrl,
            @Value("${app.casdoor.platform.client-id}") String clientId,
            @Value("${app.casdoor.platform.client-secret}") String clientSecret) {

        WebClient userBff = WebClient.builder().baseUrl(baseUrl).build();
        WebClient casdoor = WebClient.builder().baseUrl(casdoorBaseUrl).build();

        return new UserNotificationClient(
                userBff,
                FederationUsernameClientConfig.serviceTokenSupplier(casdoor, clientId, clientSecret));
    }
}
