package systems.snapdragon.actions.github;

import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Test-only factory living in the {@code github} package so it can reach
 * {@link GitHubClient}'s package-private {@code GitHubClient(WebClient)}
 * test seam from tests in other packages (e.g. the reconcile test). Lets
 * a test drive the REAL endpoint/error logic of {@link GitHubClient}
 * against a stubbed {@link ExchangeFunction} — no live HTTP server.
 */
public final class GitHubClientTestFactory {

    private GitHubClientTestFactory() {}

    public static GitHubClient withExchange(ExchangeFunction exchange) {
        WebClient http = WebClient.builder()
                .baseUrl("https://api.github.com")
                .exchangeFunction(exchange)
                .build();
        return new GitHubClient(http);
    }
}
