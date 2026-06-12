package systems.snapdragon.actions.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DomainRegistry} — the issuer-&gt;domain source of truth the
 * converter / {@link Roles} consult. MIRROR of the gateway's registry, so these
 * lock in: single-issuer back-compat ({@code DOMAIN_default}), multi-brand
 * fail-closed (unknown issuer -&gt; {@code null}), and the {@code isMultiIssuer}
 * gate that {@code SecurityConfig} swaps the decoder on.
 *
 * <p>Delineated per the test standard: USER-SITUATIONAL (one registry mode),
 * MULTIUSER (two brands), CROSS-USER (isolation / fail-closed of a foreign issuer),
 * CONCURRENT (lock-free reads under contention).
 */
class DomainRegistryTest {

    private static final String SYSTEMS = "http://casdoor-systems:8000";
    private static final String GAMES = "http://casdoor-games:8000";

    // ===================== USER-SITUATIONAL =====================
    @Nested
    class UserSituational {

        @Test
        void noConfig_everyIssuerMapsToDefault() {
            DomainRegistry r = new DomainRegistry("", "", "");
            assertThat(r.domainOf(SYSTEMS)).isEqualTo("default");
            assertThat(r.domainOf("https://anything.example")).isEqualTo("default");
            assertThat(r.domainOf(null)).isEqualTo("default");
            assertThat(r.isMultiIssuer()).isFalse();
        }

        @Test
        void singleIssuerBackCompat_loneIssuerMapsToDefault_notMultiIssuer() {
            DomainRegistry r = new DomainRegistry(SYSTEMS, "", "");
            assertThat(r.domainOf(SYSTEMS)).isEqualTo("default");
            assertThat(r.isMultiIssuer()).isFalse();
            assertThat(r.isTrusted(SYSTEMS)).isTrue();
        }

        @Test
        void trailingSlashOnIssuerIsNormalized() {
            DomainRegistry r = new DomainRegistry(SYSTEMS, "", "");
            assertThat(r.domainOf(SYSTEMS + "/")).isEqualTo("default");
        }
    }

    // ===================== MULTIUSER-SITUATIONAL =====================
    @Nested
    class MultiUser {

        private final DomainRegistry r = new DomainRegistry(
                SYSTEMS,
                SYSTEMS + "=brandx," + GAMES + "=brandy",
                "");

        @Test
        void twoBrands_eachIssuerResolvesToItsOwnDomain() {
            assertThat(r.domainOf(SYSTEMS)).isEqualTo("brandx");
            assertThat(r.domainOf(GAMES)).isEqualTo("brandy");
            assertThat(r.isMultiIssuer()).isTrue();
        }

        @Test
        void trustedIssuerSetIsExactlyTheConfiguredBrands() {
            assertThat(r.trustedIssuers()).containsExactlyInAnyOrder(SYSTEMS, GAMES);
        }

        @Test
        void domainNameIsSanitizedToDnsLabelCharset() {
            DomainRegistry weird = new DomainRegistry(
                    "", SYSTEMS + "=Brand X!", "");
            // upper-cased + non-[a-z0-9-] replaced with '-'
            assertThat(weird.domainOf(SYSTEMS)).isEqualTo("brand-x-");
        }
    }

    // ===================== CROSS-USER (ISOLATION / FAIL-CLOSED) =====================
    @Nested
    class CrossUserIsolation {

        private final DomainRegistry r = new DomainRegistry(
                SYSTEMS,
                SYSTEMS + "=brandx," + GAMES + "=brandy",
                "");

        @Test
        void unknownIssuerInMultiBrandMode_failsClosedToNull() {
            // A foreign Casdoor's iss is NOT in the allowlist -> null (deny), never
            // a silent cross-brand default.
            assertThat(r.domainOf("https://evil.example")).isNull();
            assertThat(r.isTrusted("https://evil.example")).isFalse();
        }

        @Test
        void nullIssuerInMultiBrandMode_failsClosedToNull() {
            assertThat(r.domainOf(null)).isNull();
            assertThat(r.isTrusted(null)).isFalse();
        }

        @Test
        void oneBrandsIssuerNeverResolvesToAnothersDomain() {
            assertThat(r.domainOf(SYSTEMS)).isNotEqualTo("brandy");
            assertThat(r.domainOf(GAMES)).isNotEqualTo("brandx");
        }
    }

    // ===================== CONCURRENT =====================
    @Nested
    class Concurrent {

        @Test
        void concurrentReadsAreConsistent() throws Exception {
            DomainRegistry r = new DomainRegistry(
                    SYSTEMS, SYSTEMS + "=brandx," + GAMES + "=brandy", "");
            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Boolean>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    final boolean even = i % 2 == 0;
                    futures.add(pool.submit(() -> {
                        for (int n = 0; n < 1000; n++) {
                            String iss = even ? SYSTEMS : GAMES;
                            String want = even ? "brandx" : "brandy";
                            if (!want.equals(r.domainOf(iss))) return false;
                            if (r.domainOf("https://evil.example") != null) return false;
                        }
                        return true;
                    }));
                }
                for (Future<Boolean> f : futures) {
                    assertThat(f.get()).isTrue();
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
