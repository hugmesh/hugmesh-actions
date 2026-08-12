package net.hugmesh.actions.casdoor;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the REAL {@link CasdoorClient#getUserId(String)} against a stubbed
 * {@link ExchangeFunction}: asserts it hits {@code /api/get-user} with
 * {@code id=<org>/<username>}, extracts {@code data.id}, and resolves EMPTY
 * (not an error) for an unknown user or an unusable response shape — the
 * notification writer's "skip, don't fail" contract for subject resolution.
 */
class CasdoorClientGetUserIdTest {

    private static CasdoorClient clientReturning(ClientResponse response,
                                                 AtomicReference<String> capturedUri) {
        ExchangeFunction exchange = request -> {
            if (capturedUri != null) capturedUri.set(request.url().toString());
            return Mono.just(response);
        };
        return CasdoorClientTestFactory.withExchange(exchange, "hugmesh-systems");
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    void resolvesId_andHitsGetUserEndpoint_withOrgScopedId() {
        AtomicReference<String> uri = new AtomicReference<>();
        CasdoorClient client = clientReturning(json(
                "{\"status\":\"ok\",\"data\":{\"id\":\"a1b2-uuid\",\"name\":\"alice\"}}"), uri);

        StepVerifier.create(client.getUserId("alice"))
                .expectNext("a1b2-uuid")
                .verifyComplete();

        assertThat(uri.get())
                .contains("/api/get-user")
                .contains("id=hugmesh-systems/alice");
    }

    @Test
    void unknownUser_missingData_resolvesEmpty_notError() {
        CasdoorClient client = clientReturning(json("{\"status\":\"error\",\"data\":null}"), null);
        StepVerifier.create(client.getUserId("ghost"))
                .verifyComplete();
    }

    @Test
    void missingDataField_resolvesEmpty() {
        CasdoorClient client = clientReturning(json("{\"status\":\"ok\"}"), null);
        StepVerifier.create(client.getUserId("ghost"))
                .verifyComplete();
    }

    @Test
    void blankId_resolvesEmpty() {
        CasdoorClient client = clientReturning(json(
                "{\"status\":\"ok\",\"data\":{\"id\":\"\",\"name\":\"alice\"}}"), null);
        StepVerifier.create(client.getUserId("alice"))
                .verifyComplete();
    }
}
