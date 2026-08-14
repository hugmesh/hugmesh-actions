package net.hugmesh.actions.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import net.hugmesh.actions.casdoor.CasdoorClient;
import net.hugmesh.actions.github.GitHubClient;

/**
 * Wire the two Casdoor clients the sync action needs: one for
 * hugmesh.net (source of truth for game-master role) and one
 * for hugmesh.games (target of admin role propagation).
 *
 * <p>Configuration lives in application.yml under
 * {@code app.casdoor.{systems,games}.*} — same env-var driven shape
 * as the hugmesh.games BFF and the gateways, so each deployment's
 * Casdoor endpoints + credentials live in deploy artefacts rather
 * than compiled Java.
 */
@Configuration
public class CasdoorClientsConfig {

    @Bean
    public CasdoorClient systemsCasdoorClient(
            @Value("${app.casdoor.systems.base-url}")     String baseUrl,
            @Value("${app.casdoor.systems.client-id}")    String clientId,
            @Value("${app.casdoor.systems.client-secret}") String clientSecret,
            @Value("${app.casdoor.systems.organization}") String organization) {
        return new CasdoorClient(baseUrl, clientId, clientSecret, organization, "systems");
    }

    @Bean
    public CasdoorClient gamesCasdoorClient(
            @Value("${app.casdoor.games.base-url}")     String baseUrl,
            @Value("${app.casdoor.games.client-id}")    String clientId,
            @Value("${app.casdoor.games.client-secret}") String clientSecret,
            @Value("${app.casdoor.games.organization}") String organization) {
        return new CasdoorClient(baseUrl, clientId, clientSecret, organization, "games");
    }

    /** GitHub REST client used by the {@code GitHubOrgAdminGrantAction}.
     *  The base URL is configurable so a self-hosted GitHub Enterprise
     *  install can be pointed at without changing the action's call
     *  sites; defaults to the public api.github.com. */
    @Bean
    public GitHubClient gitHubClient(
            @Value("${app.github.base-url:https://api.github.com}") String baseUrl) {
        return new GitHubClient(baseUrl);
    }
}
