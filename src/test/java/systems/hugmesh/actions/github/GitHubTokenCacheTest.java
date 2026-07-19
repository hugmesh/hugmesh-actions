package systems.hugmesh.actions.github;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubTokenCacheTest {

    /** A Clock whose instant we can advance from the test. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;
        MovableClock(Instant start) { this.now = new AtomicReference<>(start); }
        void advance(Duration by) { now.updateAndGet(i -> i.plus(by)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test
    void storesAndReturnsToken() {
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("alice", "tok-1");
        assertThat(cache.get("alice")).contains("tok-1");
        assertThat(cache.knownUsernames()).containsExactly("alice");
    }

    @Test
    void ignoresBlankUsernameOrToken() {
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("", "tok");
        cache.put("alice", "");
        cache.put(null, "tok");
        cache.put("bob", null);
        assertThat(cache.size()).isZero();
    }

    @Test
    void laterPutRefreshesToken() {
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("alice", "old");
        cache.put("alice", "new");
        assertThat(cache.get("alice")).contains("new");
    }

    @Test
    void evictRemovesToken() {
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24));
        cache.put("alice", "tok");
        cache.evict("alice");
        assertThat(cache.get("alice")).isEmpty();
    }

    @Test
    void expiredTokenIsTreatedAsUnknown() {
        MovableClock clock = new MovableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(1), clock);
        cache.put("alice", "tok");
        assertThat(cache.get("alice")).contains("tok");

        clock.advance(Duration.ofHours(2));
        assertThat(cache.get("alice")).isEmpty();
        assertThat(cache.knownUsernames()).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void unexpiredTokenSurvivesWithinTtl() {
        MovableClock clock = new MovableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GitHubTokenCache cache = new GitHubTokenCache(Duration.ofHours(24), clock);
        cache.put("alice", "tok");
        clock.advance(Duration.ofHours(23));
        assertThat(cache.get("alice")).contains("tok");
    }

    @Test
    void nonPositiveTtlFallsBackToDefault() {
        GitHubTokenCache zero = new GitHubTokenCache(Duration.ZERO);
        zero.put("alice", "tok");
        assertThat(zero.get("alice")).contains("tok");
    }
}
