package systems.snapdragon.actions.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the REAL {@link OidcRoleHookClient} against a stubbed
 * {@link ExchangeFunction}: it forwards the service bearer + the correct JSON
 * body to {@code POST /bff/internal/oidc/first-login}, parses
 * {@link FirstLoginResponse}, and — critically — FAILS OPEN (resolves to an
 * empty no-op response, never throws, never fabricates roles) on any 5xx /
 * timeout / transport error so a probe error can never break the tick.
 */
class OidcRoleHookClientTest {

    private static final Supplier<Mono<String>> TOKEN = () -> Mono.just("service-jwt");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static OidcRoleHookClient clientReturning(ClientResponse response) {
        ExchangeFunction exchange = request -> Mono.just(response);
        WebClient web = WebClient.builder()
                .baseUrl("http://admin-bff")
                .exchangeFunction(exchange)
                .build();
        return new OidcRoleHookClient(web, TOKEN);
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    void sendsBearer_andHitsFirstLoginUri_parsesResponse() {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            auth.set(request.headers().getFirst("Authorization"));
            uri.set(request.url().toString());
            return Mono.just(json(HttpStatus.OK, "{\"assignedRoles\":[\"user\",\"member\"]}"));
        };
        WebClient web = WebClient.builder()
                .baseUrl("http://admin-bff")
                .exchangeFunction(exchange)
                .build();
        OidcRoleHookClient client = new OidcRoleHookClient(web, TOKEN);

        StepVerifier.create(client.applyDefaultRoles("default", "google", "alice"))
                .assertNext(resp -> assertThat(resp.assignedRoles())
                        .containsExactly("user", "member"))
                .verifyComplete();

        assertThat(auth.get()).isEqualTo("Bearer service-jwt");
        assertThat(uri.get()).isEqualTo("http://admin-bff/bff/internal/oidc/first-login");
    }

    @Test
    void sendsCorrectJsonBody() throws Exception {
        AtomicReference<String> bodyJson = new AtomicReference<>();
        // Replay the ClientRequest into a MockClientHttpRequest, capturing the
        // serialized body bytes via a write handler, THEN return the canned
        // response. The write handler reads each buffer as it is written so the
        // captured body is complete by the time writeTo() resolves.
        ExchangeFunction exchange = request -> {
            MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
            mock.setWriteHandler(buffers -> DataBufferUtils.join(buffers)
                    .doOnNext(buf -> {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        DataBufferUtils.release(buf);
                        bodyJson.set(new String(bytes, StandardCharsets.UTF_8));
                    })
                    .then());
            return request.writeTo(mock, ExchangeStrategies.withDefaults())
                    .thenReturn(json(HttpStatus.OK, "{\"assignedRoles\":[]}"));
        };
        WebClient web = WebClient.builder()
                .baseUrl("http://admin-bff")
                .exchangeFunction(exchange)
                .build();
        OidcRoleHookClient client = new OidcRoleHookClient(web, TOKEN);

        StepVerifier.create(client.applyDefaultRoles("default", "google", "alice"))
                .expectNextCount(1)
                .verifyComplete();

        Map<?, ?> sent = MAPPER.readValue(bodyJson.get(), Map.class);
        assertThat(sent.get("domain")).isEqualTo("default");
        assertThat(sent.get("provider")).isEqualTo("google");
        assertThat(sent.get("casdoorUsername")).isEqualTo("alice");
    }

    @Test
    void emptyRoles_parsesToEmptyList() {
        OidcRoleHookClient client = clientReturning(
                json(HttpStatus.OK, "{\"assignedRoles\":[]}"));
        StepVerifier.create(client.applyDefaultRoles("default", "google", "bob"))
                .assertNext(resp -> assertThat(resp.assignedRoles()).isEmpty())
                .verifyComplete();
    }

    @Test
    void serverError_failsOpen_noThrow_emptyRoles() {
        OidcRoleHookClient client = clientReturning(
                json(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));
        StepVerifier.create(client.applyDefaultRoles("default", "google", "carol"))
                .assertNext(resp -> assertThat(resp.assignedRoles()).isEmpty())
                .verifyComplete();
    }

    @Test
    void transportError_failsOpen_noThrow_emptyRoles() {
        ExchangeFunction boom = request ->
                Mono.error(new java.net.ConnectException("connection refused"));
        WebClient web = WebClient.builder()
                .baseUrl("http://admin-bff")
                .exchangeFunction(boom)
                .build();
        OidcRoleHookClient client = new OidcRoleHookClient(web, TOKEN);

        StepVerifier.create(client.applyDefaultRoles("default", "google", "dave"))
                .assertNext(resp -> assertThat(resp.assignedRoles()).isEmpty())
                .verifyComplete();
    }
}
