package systems.snapdragon.actions.casdoor;

import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Test-only factory living in the {@code casdoor} package so tests in other
 * packages can reach {@link CasdoorClient}'s package-private
 * {@code CasdoorClient(WebClient, String, String)} test seam. Lets a test
 * drive the REAL endpoint/parse logic of {@link CasdoorClient} against a
 * stubbed {@link ExchangeFunction} — no live Casdoor server.
 */
public final class CasdoorClientTestFactory {

    private CasdoorClientTestFactory() {}

    public static CasdoorClient withExchange(ExchangeFunction exchange, String organization) {
        WebClient http = WebClient.builder()
                .baseUrl("http://casdoor")
                .exchangeFunction(exchange)
                .build();
        return new CasdoorClient(http, organization, "test");
    }
}
