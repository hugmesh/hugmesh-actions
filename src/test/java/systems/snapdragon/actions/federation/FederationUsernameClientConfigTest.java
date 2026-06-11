package systems.snapdragon.actions.federation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link FederationUsernameClientConfig}: the bean is GATED config-on (so
 * dev/test/CI leave it absent and the registration gate degrades to the local
 * exact reserved fast-path), and its client-credentials service-token supplier
 * mints + caches a Casdoor bearer.
 */
class FederationUsernameClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(FederationUsernameClientConfig.class)
            .withPropertyValues(
                    "app.casdoor.systems.base-url=http://casdoor:8000",
                    "app.casdoor.systems.client-id=snapdragon-actions",
                    "app.casdoor.systems.client-secret=secret");

    @Test
    void beanIsAbsentByDefault_gateOff() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(FederationUsernameClient.class));
    }

    @Test
    void beanIsPresentWhenGateOn() {
        runner.withPropertyValues("app.federation.username-check.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(FederationUsernameClient.class));
    }

    @Test
    void serviceTokenSupplier_mintsThenCaches() {
        AtomicInteger mintCalls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            mintCalls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"access_token\":\"svc-jwt\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
                    .build());
        };
        WebClient casdoor = WebClient.builder()
                .baseUrl("http://casdoor:8000")
                .exchangeFunction(exchange)
                .build();

        Supplier<Mono<String>> supplier =
                FederationUsernameClientConfig.serviceTokenSupplier(casdoor, "id", "secret");

        // First call mints; second call serves the cached token (no second mint).
        StepVerifier.create(supplier.get()).expectNext("svc-jwt").verifyComplete();
        StepVerifier.create(supplier.get()).expectNext("svc-jwt").verifyComplete();
        assertThat(mintCalls.get()).isEqualTo(1);
    }
}
