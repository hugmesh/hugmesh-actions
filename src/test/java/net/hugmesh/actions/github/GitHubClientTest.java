package net.hugmesh.actions.github;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level coverage for the 404-vs-transient classification that
 * gates revoke-on-transient-error (FIX 2). A DEFINITIVE 404 means
 * "genuinely not a member" → reach is false; ANY other error is
 * inconclusive and must NOT be collapsed to "no reach".
 */
class GitHubClientTest {

    @Test
    void definitiveNotMember_isTrue_onlyFor404() {
        WebClientResponseException notFound = WebClientResponseException.create(
                HttpStatus.NOT_FOUND.value(), "Not Found", null, null, null);
        assertThat(GitHubClient.isDefinitiveNotMember(notFound)).isTrue();
    }

    @Test
    void definitiveNotMember_isFalse_forTransient5xx() {
        WebClientResponseException serviceUnavailable = WebClientResponseException.create(
                HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable", null, null, null);
        WebClientResponseException badGateway = WebClientResponseException.create(
                HttpStatus.BAD_GATEWAY.value(), "Bad Gateway", null, null, null);
        // Secondary-rate-limit shows up as a 403.
        WebClientResponseException rateLimited = WebClientResponseException.create(
                HttpStatus.FORBIDDEN.value(), "rate limited", null, null, null);

        assertThat(GitHubClient.isDefinitiveNotMember(serviceUnavailable)).isFalse();
        assertThat(GitHubClient.isDefinitiveNotMember(badGateway)).isFalse();
        assertThat(GitHubClient.isDefinitiveNotMember(rateLimited)).isFalse();
    }

    @Test
    void definitiveNotMember_isFalse_forNetworkBlipAndArbitraryError() {
        // A raw network failure (no HTTP status at all) is inconclusive.
        Throwable network = new ConnectException("connection reset");
        assertThat(GitHubClient.isDefinitiveNotMember(network)).isFalse();
        assertThat(GitHubClient.isDefinitiveNotMember(new IOException("read timed out"))).isFalse();
        assertThat(GitHubClient.isDefinitiveNotMember(new RuntimeException("boom"))).isFalse();
    }
}
