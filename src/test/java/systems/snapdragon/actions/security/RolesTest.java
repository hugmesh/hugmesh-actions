package systems.snapdragon.actions.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Roles} — the JWT role/domain extraction helpers MIRRORED
 * from the gateway. Lock in: {@code isSuperadmin}/{@code isImageBuilder} read the
 * roles claim only; {@code domainOf(Jwt,registry)} from the issuer;
 * {@code domainOf(authorities)} FAILS CLOSED on zero / &gt;1 {@code DOMAIN_}.
 *
 * <p>Delineated per the test standard: USER-SITUATIONAL (one principal's roles),
 * MULTIUSER (different role-holders), CROSS-USER (a forged claim can NOT escalate
 * across the isolation boundary), CONCURRENT (stateless helpers under contention).
 */
class RolesTest {

    private static final String SYSTEMS = "http://casdoor-systems:8000";
    private static final String GAMES = "http://casdoor-games:8000";

    private static Jwt jwt(String issuer, Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("alice");
        if (issuer != null) b.issuer(issuer);
        claims.forEach(b::claim);
        b.claim("sub", "alice");
        return b.build();
    }

    private static GrantedAuthority a(String s) {
        return new SimpleGrantedAuthority(s);
    }

    // ===================== USER-SITUATIONAL =====================
    @Nested
    class UserSituational {

        @Test
        void superadminRecognisedInBothSpellings() {
            assertThat(Roles.isSuperadmin(jwt(SYSTEMS, Map.of("roles", List.of("superadmin"))))).isTrue();
            assertThat(Roles.isSuperadmin(jwt(SYSTEMS, Map.of("roles", List.of("super-admin"))))).isTrue();
            assertThat(Roles.isSuperadmin(jwt(SYSTEMS, Map.of("roles", List.of("super_admin"))))).isTrue();
        }

        @Test
        void plainUserIsNotSuperadminNorImageBuilder() {
            Jwt j = jwt(SYSTEMS, Map.of("roles", List.of("user")));
            assertThat(Roles.isSuperadmin(j)).isFalse();
            assertThat(Roles.isImageBuilder(j)).isFalse();
        }

        @Test
        void imageBuilderRecognisedInBothSpellings() {
            assertThat(Roles.isImageBuilder(jwt(SYSTEMS, Map.of("roles", List.of("image-builder"))))).isTrue();
            assertThat(Roles.isImageBuilder(jwt(SYSTEMS, Map.of("roles", List.of("image_builder"))))).isTrue();
        }

        @Test
        void adminAndGameMasterRecognised() {
            assertThat(Roles.isAdmin(jwt(SYSTEMS, Map.of("roles", List.of("admin"))))).isTrue();
            assertThat(Roles.isAdmin(jwt(SYSTEMS, Map.of("isAdmin", true)))).isTrue();
            assertThat(Roles.isGameMaster(jwt(SYSTEMS, Map.of("roles", List.of("game-master"))))).isTrue();
        }

        @Test
        void nullJwtIsSafe() {
            assertThat(Roles.isSuperadmin(null)).isFalse();
            assertThat(Roles.isImageBuilder(null)).isFalse();
            assertThat(Roles.isAdmin(null)).isFalse();
            assertThat(Roles.fromJwt(null)).isEmpty();
        }
    }

    // ===================== MULTIUSER-SITUATIONAL =====================
    @Nested
    class MultiUser {

        private final DomainRegistry registry = new DomainRegistry(
                SYSTEMS, SYSTEMS + "=brandx," + GAMES + "=brandy", "");

        @Test
        void domainOf_isKeyedOnTheIssuer_perBrand() {
            assertThat(Roles.domainOf(jwt(SYSTEMS, Map.of()), registry)).isEqualTo("brandx");
            assertThat(Roles.domainOf(jwt(GAMES, Map.of()), registry)).isEqualTo("brandy");
        }

        @Test
        void singleIssuerCluster_domainOfIsDefault() {
            DomainRegistry single = new DomainRegistry(SYSTEMS, "", "");
            assertThat(Roles.domainOf(jwt(SYSTEMS, Map.of()), single)).isEqualTo("default");
        }

        @Test
        void domainOfAuthorities_readsTheSingleDomainAuthority() {
            assertThat(Roles.domainOf(List.of(a("ROLE_USER"), a("DOMAIN_brandx")))).isEqualTo("brandx");
        }
    }

    // ===================== CROSS-USER (ISOLATION / NO ESCALATION) =====================
    @Nested
    class CrossUserIsolation {

        private final DomainRegistry registry = new DomainRegistry(
                SYSTEMS, SYSTEMS + "=brandx," + GAMES + "=brandy", "");

        @Test
        void rolesClaimNamedDomain_doesNotForgeATenantViaDomainOfJwt() {
            // A roles entry "DOMAIN_brandy" can never change the issuer-derived domain.
            Jwt j = jwt(SYSTEMS, Map.of("roles", List.of("DOMAIN_brandy")));
            assertThat(Roles.domainOf(j, registry)).isEqualTo("brandx");
        }

        @Test
        void unknownIssuer_domainOfJwtFailsClosedToNull() {
            assertThat(Roles.domainOf(jwt("https://evil.example", Map.of()), registry)).isNull();
        }

        @Test
        void domainOfAuthorities_failsClosedOnZeroDomain() {
            // A hand-built / pre-converter token with no DOMAIN_ -> null (deny).
            assertThat(Roles.domainOf(List.of(a("ROLE_USER"), a("ROLE_ADMIN")))).isNull();
        }

        @Test
        void domainOfAuthorities_failsClosedOnInjectedSecondDomain() {
            // The injection signature: a forged DOMAIN_brandy alongside the genuine
            // issuer DOMAIN_brandx -> null (never pick the first).
            assertThat(Roles.domainOf(List.of(
                    a("ROLE_USER"), a("DOMAIN_brandx"), a("DOMAIN_brandy")))).isNull();
        }

        @Test
        void domainOfAuthorities_nullCollectionFailsClosed() {
            assertThat(Roles.domainOf((java.util.Collection<GrantedAuthority>) null)).isNull();
        }

        @Test
        void forgedSuperadminPermissionStringIsNotARole() {
            // isSuperadmin reads the roles claim ONLY — a permissions:["ROLE_SUPERADMIN"]
            // (which the converter strips anyway) is never a role here.
            Jwt j = jwt(SYSTEMS, Map.of("permissions", List.of("ROLE_SUPERADMIN")));
            assertThat(Roles.isSuperadmin(j)).isFalse();
        }
    }

    // ===================== CONCURRENT =====================
    @Nested
    class Concurrent {

        @Test
        void statelessHelpersAreThreadSafe() throws Exception {
            DomainRegistry registry = new DomainRegistry(
                    SYSTEMS, SYSTEMS + "=brandx," + GAMES + "=brandy", "");
            Jwt sa = jwt(SYSTEMS, Map.of("roles", List.of("superadmin")));
            Jwt brandy = jwt(GAMES, Map.of("roles", List.of("admin")));

            int threads = 24;
            var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        for (int n = 0; n < 1000; n++) {
                            if (!Roles.isSuperadmin(sa)) return false;
                            if (Roles.isSuperadmin(brandy)) return false;
                            if (!"brandy".equals(Roles.domainOf(brandy, registry))) return false;
                        }
                        return true;
                    }));
                }
                for (var f : futures) assertThat(f.get()).isTrue();
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
