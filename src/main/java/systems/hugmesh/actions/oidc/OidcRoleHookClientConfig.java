package systems.hugmesh.actions.oidc;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Wires the {@link OidcRoleHookClient} the OIDC default-role registration probe
 * calls — a WebClient against hugmesh-admin-bff-api's
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
 * <p><b>Auth — internal surface (X-Internal-Token + mTLS), NOT a Casdoor bearer.</b>
 * The first-login endpoint lives under admin-bff's {@code /bff/internal/**}, which is
 * gated by in-cluster mTLS ({@code server.ssl.client-auth=need}) + a constant-time
 * {@code X-Internal-Token} header — it is NOT a {@code ROLE_SERVICE} JWT surface. So this
 * client presents the shared {@code X-Internal-Token}
 * ({@code app.actions.oidc-default-roles.internal-token}) and, when this service's own
 * {@code server.ssl.enabled=true}, its WebClient carries the cluster mTLS client identity
 * (the SAME {@code keystore.p12} = our Vault-PKI cert + {@code truststore.p12} = cluster CA
 * the server side already mounts). When ssl is disabled (local/dev/CI) the WebClient is
 * plaintext, matching the rest of the dev wiring.
 *
 * <p><b>Base URL</b> defaults to the in-cluster DNS of admin-bff-api,
 * env-overridable via {@code APP_OIDC_DEFAULT_ROLES_ADMIN_BFF_BASE_URL}.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.actions.oidc-default-roles", name = "enabled", havingValue = "true")
public class OidcRoleHookClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OidcRoleHookClientConfig.class);

    @Bean
    public OidcRoleHookClient oidcRoleHookClient(
            @Value("${app.actions.oidc-default-roles.admin-bff-base-url:http://hugmesh-admin-bff-api.hugmesh-backend.svc.cluster.local:8080}")
            String adminBffBaseUrl,
            @Value("${app.actions.oidc-default-roles.internal-token:}") String internalToken,
            // Reuse this service's own mTLS identity (the server-side stores) for the
            // outbound client connector. Disabled (plaintext) in local/dev/CI.
            @Value("${server.ssl.enabled:false}") boolean sslEnabled,
            @Value("${server.ssl.key-store:/stores/keystore.p12}") String keyStore,
            @Value("${server.ssl.key-store-password:}") String keyStorePassword,
            @Value("${server.ssl.trust-store:/stores/truststore.p12}") String trustStore,
            @Value("${server.ssl.trust-store-password:}") String trustStorePassword) {

        WebClient.Builder builder = WebClient.builder().baseUrl(adminBffBaseUrl);
        if (sslEnabled) {
            // mTLS: present our cluster cert (keystore) + trust the cluster CA (truststore),
            // so the admin-bff client-auth=need handshake accepts us and we verify it in turn.
            builder = builder.clientConnector(mtlsConnector(
                    keyStore, keyStorePassword, trustStore, trustStorePassword));
            log.info("[oidc-role-hook] outbound mTLS enabled (keystore={}, truststore={})", keyStore, trustStore);
        } else {
            log.info("[oidc-role-hook] outbound mTLS DISABLED (server.ssl.enabled=false) — plaintext to {}",
                    adminBffBaseUrl);
        }
        return new OidcRoleHookClient(builder.build(), internalToken);
    }

    /**
     * Build a reactor-netty client connector that presents our PKCS12 client identity and
     * trusts the cluster CA. Loaded once at bean creation; a misconfigured/missing store
     * surfaces here as a clear startup failure (fail-closed) rather than a silent plaintext
     * downgrade — correct for an internal privilege-granting call.
     */
    private static ReactorClientHttpConnector mtlsConnector(
            String keyStorePath, String keyStorePassword,
            String trustStorePath, String trustStorePassword) {
        try {
            char[] kpw = keyStorePassword == null ? new char[0] : keyStorePassword.toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(keyStorePath))) {
                ks.load(in, kpw);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, kpw);

            KeyStore ts = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(trustStorePath))) {
                ts.load(in, trustStorePassword == null ? new char[0] : trustStorePassword.toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);

            SslContext sslContext = SslContextBuilder.forClient()
                    .keyManager(kmf)
                    .trustManager(tmf)
                    .build();
            HttpClient httpClient = HttpClient.create().secure(spec -> spec.sslContext(sslContext));
            return new ReactorClientHttpConnector(httpClient);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to build the oidc-role-hook outbound mTLS connector from "
                            + keyStorePath + " / " + trustStorePath, e);
        }
    }
}
