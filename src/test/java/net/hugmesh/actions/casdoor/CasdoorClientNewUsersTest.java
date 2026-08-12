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
 * Drives the REAL {@link CasdoorClient#listNewUsers(int)} against a stubbed
 * {@link ExchangeFunction} (via the package-private test seam): asserts it
 * parses {@code data[]} into {@link CasdoorClient.NewUser} records, hits the
 * right get-sorted-users URI, and tolerates a missing / empty / non-list
 * {@code data} (returns an empty list, never errors).
 */
class CasdoorClientNewUsersTest {

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
    void parsesDataIntoNewUserRecords_andHitsSortedUsersEndpoint() {
        AtomicReference<String> uri = new AtomicReference<>();
        CasdoorClient client = clientReturning(json(
                "{\"status\":\"ok\",\"data\":["
                + "{\"name\":\"alice\",\"signupApplication\":\"app-google\",\"createdTime\":\"2026-06-19T10:00:00Z\"},"
                + "{\"name\":\"bob\",\"signupApplication\":\"app-github\",\"createdTime\":\"2026-06-19T09:00:00Z\"}"
                + "]}"), uri);

        StepVerifier.create(client.listNewUsers(50))
                .assertNext(users -> {
                    assertThat(users).hasSize(2);
                    assertThat(users.get(0).name()).isEqualTo("alice");
                    assertThat(users.get(0).signupApplication()).isEqualTo("app-google");
                    assertThat(users.get(0).createdTime()).isEqualTo("2026-06-19T10:00:00Z");
                    assertThat(users.get(1).name()).isEqualTo("bob");
                })
                .verifyComplete();

        assertThat(uri.get())
                .contains("/api/get-sorted-users")
                .contains("owner=hugmesh-systems")
                .contains("sorter=created_time")
                .contains("order=descend")
                .contains("limit=50");
    }

    @Test
    void tolerates_emptyData_returnsEmptyList() {
        CasdoorClient client = clientReturning(json("{\"status\":\"ok\",\"data\":[]}"), null);
        StepVerifier.create(client.listNewUsers(50))
                .assertNext(users -> assertThat(users).isEmpty())
                .verifyComplete();
    }

    @Test
    void tolerates_nullData_returnsEmptyList() {
        CasdoorClient client = clientReturning(json("{\"status\":\"ok\",\"data\":null}"), null);
        StepVerifier.create(client.listNewUsers(50))
                .assertNext(users -> assertThat(users).isEmpty())
                .verifyComplete();
    }

    @Test
    void tolerates_missingData_returnsEmptyList() {
        CasdoorClient client = clientReturning(json("{\"status\":\"ok\"}"), null);
        StepVerifier.create(client.listNewUsers(50))
                .assertNext(users -> assertThat(users).isEmpty())
                .verifyComplete();
    }

    @Test
    void dropsRowsMissingName_andTolersatesMissingOptionalFields() {
        CasdoorClient client = clientReturning(json(
                "{\"status\":\"ok\",\"data\":["
                + "{\"signupApplication\":\"app-google\"},"          // no name -> dropped
                + "{\"name\":\"\"},"                                  // blank name -> dropped
                + "{\"name\":\"carol\"}"                              // no signup/created -> nulls
                + "]}"), null);

        StepVerifier.create(client.listNewUsers(50))
                .assertNext(users -> {
                    assertThat(users).hasSize(1);
                    assertThat(users.get(0).name()).isEqualTo("carol");
                    assertThat(users.get(0).signupApplication()).isNull();
                    assertThat(users.get(0).createdTime()).isNull();
                })
                .verifyComplete();
    }
}
