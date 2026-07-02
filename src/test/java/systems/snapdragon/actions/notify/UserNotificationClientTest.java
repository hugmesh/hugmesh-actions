package systems.snapdragon.actions.notify;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the REAL {@link UserNotificationClient#deliver} against a stubbed
 * {@link ExchangeFunction}: asserts it POSTs
 * {@code /bff/user/internal/notifications} bearing the service token,
 * serializes the {@link UserNotificationClient.Delivery} body field-for-field,
 * and parses user-bff's honest {@link UserNotificationClient.Outcome}.
 */
class UserNotificationClientTest {

    private static UserNotificationClient client(ExchangeFunction exchange, String token) {
        WebClient http = WebClient.builder()
                .baseUrl("http://user-bff")
                .exchangeFunction(exchange)
                .build();
        return new UserNotificationClient(http, () -> Mono.just(token));
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    void deliver_postsInternalEndpoint_withBearerAndBody() {
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            uri.set(request.url().toString());
            auth.set(request.headers().getFirst("Authorization"));
            return Mono.just(json("{\"delivered\":true,\"deduped\":false,\"reason\":null}"));
        };
        UserNotificationClient c = client(exchange, "svc-token-123");

        UserNotificationClient.Delivery delivery = new UserNotificationClient.Delivery(
                "snapdragon", "user-subject-uuid", "role-change",
                "Role granted: admin", "body text",
                "actions_gm_sync", "grant:alice:1");

        StepVerifier.create(c.deliver(delivery))
                .assertNext(outcome -> {
                    assertThat(outcome.delivered()).isTrue();
                    assertThat(outcome.deduped()).isFalse();
                    assertThat(outcome.reason()).isNull();
                })
                .verifyComplete();

        assertThat(uri.get()).endsWith("/bff/user/internal/notifications");
        assertThat(auth.get()).isEqualTo("Bearer svc-token-123");
    }

    @Test
    void deliver_parsesSuppressedOutcome() {
        ExchangeFunction exchange = request ->
                Mono.just(json("{\"delivered\":false,\"deduped\":false,\"reason\":\"suppressed\"}"));
        UserNotificationClient c = client(exchange, "svc-token");

        UserNotificationClient.Delivery delivery = new UserNotificationClient.Delivery(
                "snapdragon", "subj", "role-change", "t", "b", "src", "id-1");

        StepVerifier.create(c.deliver(delivery))
                .assertNext(outcome -> {
                    assertThat(outcome.delivered()).isFalse();
                    assertThat(outcome.reason()).isEqualTo("suppressed");
                })
                .verifyComplete();
    }

    @Test
    void deliver_propagatesTransportErrors_forCallerToAbsorb() {
        ExchangeFunction exchange = request -> Mono.error(new RuntimeException("connection refused"));
        UserNotificationClient c = client(exchange, "svc-token");

        UserNotificationClient.Delivery delivery = new UserNotificationClient.Delivery(
                "snapdragon", "subj", "role-change", "t", "b", "src", "id-1");

        StepVerifier.create(c.deliver(delivery))
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && e.getMessage().contains("connection refused"))
                .verify();
    }
}
