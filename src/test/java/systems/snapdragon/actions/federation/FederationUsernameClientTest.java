package systems.snapdragon.actions.federation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link FederationUsernameClient}: it forwards a service
 * bearer, maps the registry's JSON verdict onto {@link UsernameAvailability},
 * and — critically — FAILS CLOSED (resolves to collide=true) on any transport /
 * status / empty-body failure so a federation outage can never let a confusable
 * username through. Driven against a stubbed {@link ExchangeFunction}, no live
 * federation server.
 */
class FederationUsernameClientTest {

    private static final Supplier<Mono<String>> TOKEN = () -> Mono.just("service-jwt");

    private static FederationUsernameClient clientReturning(ClientResponse response,
                                                            AtomicReference<String> capturedAuth,
                                                            AtomicReference<String> capturedUri) {
        ExchangeFunction exchange = request -> {
            if (capturedAuth != null) {
                capturedAuth.set(request.headers().getFirst("Authorization"));
            }
            if (capturedUri != null) {
                capturedUri.set(request.url().toString());
            }
            return Mono.just(response);
        };
        WebClient web = WebClient.builder()
                .baseUrl("http://federation")
                .exchangeFunction(exchange)
                .build();
        return new FederationUsernameClient(web, TOKEN);
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    void availableUsername_mapsToNoCollide() {
        FederationUsernameClient client = clientReturning(
                json(HttpStatus.OK,
                        "{\"available\":true,\"collide\":false,\"reason\":null,"
                        + "\"matchedWith\":null,\"skeleton\":\"magus\"}"),
                null, null);

        StepVerifier.create(client.availability("magus"))
                .assertNext(a -> {
                    assertThat(a.available()).isTrue();
                    assertThat(a.collide()).isFalse();
                    assertThat(a.skeleton()).isEqualTo("magus");
                })
                .verifyComplete();
    }

    @Test
    void confusableUsername_mapsToCollideWithReasonAndMatch() {
        FederationUsernameClient client = clientReturning(
                json(HttpStatus.OK,
                        "{\"available\":false,\"collide\":true,\"reason\":\"confusable\","
                        + "\"matchedWith\":\"admin\",\"skeleton\":\"admin\"}"),
                null, null);

        StepVerifier.create(client.availability("4dmin"))
                .assertNext(a -> {
                    assertThat(a.collide()).isTrue();
                    assertThat(a.reason()).isEqualTo("confusable");
                    assertThat(a.matchedWith()).isEqualTo("admin");
                })
                .verifyComplete();
    }

    @Test
    void forwardsServiceBearerAndEncodesCandidateInPath() {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        FederationUsernameClient client = clientReturning(
                json(HttpStatus.OK, "{\"available\":true,\"collide\":false}"),
                auth, uri);

        StepVerifier.create(client.availability("alice")).expectNextCount(1).verifyComplete();

        assertThat(auth.get()).isEqualTo("Bearer service-jwt");
        assertThat(uri.get())
                .isEqualTo("http://federation/api/federation/username/alice/availability");
    }

    @Test
    void serverError_failsClosed_collideTrue() {
        FederationUsernameClient client = clientReturning(
                json(HttpStatus.SERVICE_UNAVAILABLE, "{}"), null, null);

        StepVerifier.create(client.availability("magus"))
                .assertNext(a -> {
                    assertThat(a.collide()).isTrue();
                    assertThat(a.available()).isFalse();
                    assertThat(a.reason()).isEqualTo("unknown");
                })
                .verifyComplete();
    }

    @Test
    void transportError_failsClosed_collideTrue() {
        ExchangeFunction boom = request -> Mono.error(new java.net.ConnectException("connection refused"));
        WebClient web = WebClient.builder()
                .baseUrl("http://federation")
                .exchangeFunction(boom)
                .build();
        FederationUsernameClient client = new FederationUsernameClient(web, TOKEN);

        StepVerifier.create(client.availability("magus"))
                .assertNext(a -> assertThat(a.collide()).isTrue())
                .verifyComplete();
    }
}
