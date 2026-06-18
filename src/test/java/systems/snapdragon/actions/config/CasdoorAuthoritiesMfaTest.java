package systems.snapdragon.actions.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import systems.snapdragon.actions.security.DomainRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D6+D8 MFA_SATISFIED converter parity tests — fan-out of snapdragon-gateway's
 * {@code CasdoorAuthoritiesTest} MFA block (PR #40). Proves the {@code MFA_SATISFIED}
 * marker is derived BYTE-IDENTICALLY here: emitted ONLY from the validated
 * {@code mfa_satisfied_at} claim behind {@code app.mfa.enabled}, NON-role, never
 * from roles/permissions, and (ships-dark) NEVER produced when the flag is off so
 * the legacy authority set is byte-identical. An explicit {@code now} makes the
 * freshness window deterministic.
 */
class CasdoorAuthoritiesMfaTest {

    private static final long WINDOW = 600_000L;   // 10 min
    private static final long NOW = 1_750_000_000_000L;

    private static Jwt jwt(String issuer, Map<String, Object> claims) {
        return Jwt.withTokenValue("test")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .subject("u1")
            .issuer(issuer)
            .claims(c -> c.putAll(claims))
            .build();
    }

    private static List<String> names(List<GrantedAuthority> a) {
        return a.stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt mfaJwt(String issuer, String satisfiedAt) {
        return jwt(issuer, Map.of("mfa_satisfied_at", satisfiedAt));
    }

    @Test
    void mfaEnabled_freshStamp_emitsMfaSatisfiedMarker_nonRole() {
        DomainRegistry reg = new DomainRegistry("http://casdoor:8000", "", "");
        var on = new CasdoorAuthorities.MfaConfig(true, WINDOW);
        var auth = CasdoorAuthorities.authorities(
                mfaJwt("http://casdoor:8000", Long.toString(NOW - 60_000L)), reg, on, NOW);
        assertThat(names(auth)).contains("MFA_SATISFIED");
        // NON-role authority — no ROLE_ prefix, so it can never satisfy a
        // hasRole()/route-access role check by accident.
        assertThat(names(auth)).doesNotContain("ROLE_MFA_SATISFIED");
    }

    @Test
    void mfaEnabled_staleFutureOrBlankStamp_noMarker() {
        DomainRegistry reg = new DomainRegistry("http://casdoor:8000", "", "");
        var on = new CasdoorAuthorities.MfaConfig(true, WINDOW);
        // Stale (past the window).
        assertThat(names(CasdoorAuthorities.authorities(
                mfaJwt("http://casdoor:8000", Long.toString(NOW - WINDOW - 1)), reg, on, NOW)))
                .doesNotContain("MFA_SATISFIED");
        // Future (forged-future / skew) — rejected.
        assertThat(names(CasdoorAuthorities.authorities(
                mfaJwt("http://casdoor:8000", Long.toString(NOW + 60_000L)), reg, on, NOW)))
                .doesNotContain("MFA_SATISFIED");
        // Blank / non-numeric — fail-closed.
        assertThat(names(CasdoorAuthorities.authorities(
                mfaJwt("http://casdoor:8000", "   "), reg, on, NOW)))
                .doesNotContain("MFA_SATISFIED");
        assertThat(names(CasdoorAuthorities.authorities(
                mfaJwt("http://casdoor:8000", "not-a-number"), reg, on, NOW)))
                .doesNotContain("MFA_SATISFIED");
    }

    @Test
    void mfaDisabled_darkDefault_neverEmitsMarker_evenWithFreshStamp() {
        // app.mfa.enabled=false (the dark default) => byte-identical legacy authority
        // set; the marker is NEVER produced even with a perfectly fresh stamp.
        DomainRegistry reg = new DomainRegistry("http://casdoor:8000", "", "");
        var auth = CasdoorAuthorities.authorities(
                mfaJwt("http://casdoor:8000", Long.toString(NOW)), reg,
                CasdoorAuthorities.MfaConfig.DISABLED, NOW);
        assertThat(names(auth)).doesNotContain("MFA_SATISFIED");
        // The convenience overload (legacy, no MfaConfig) is also dark.
        assertThat(names(CasdoorAuthorities.authorities(
                mfaJwt("http://casdoor:8000", Long.toString(NOW)), reg)))
                .doesNotContain("MFA_SATISFIED");
    }

    @Test
    void forgedMfaSatisfiedPermission_isDropped_evenWhenMfaEnabled() {
        // A forged permissions:["MFA_SATISFIED"] must NEVER inject the marker — the
        // reserved-prefix guard drops it; the marker comes ONLY from the validated
        // claim. Here there is NO valid mfa_satisfied_at claim, so the only way
        // MFA_SATISFIED could appear is via the forged permission (it must not).
        DomainRegistry reg = new DomainRegistry("http://casdoor:8000", "", "");
        var on = new CasdoorAuthorities.MfaConfig(true, WINDOW);
        var auth = CasdoorAuthorities.authorities(
                jwt("http://casdoor:8000", Map.of(
                        "permissions", List.of("MFA_SATISFIED", "mfa_satisfied", " MFA_SATISFIED "))),
                reg, on, NOW);
        assertThat(names(auth)).doesNotContain("MFA_SATISFIED");
        assertThat(names(auth)).noneMatch(s -> s.toUpperCase().contains("MFA_SATISFIED"));
    }

    @Test
    void forgedMfaSatisfiedPermission_doesNotSurvive_butValidClaimDoes() {
        // Both at once: a forged permission AND a genuine fresh claim. Exactly ONE
        // MFA_SATISFIED authority survives — the converter's, from the claim; the
        // permission entry is dropped (not double-added, not the source).
        DomainRegistry reg = new DomainRegistry("http://casdoor:8000", "", "");
        var on = new CasdoorAuthorities.MfaConfig(true, WINDOW);
        var auth = CasdoorAuthorities.authorities(
                jwt("http://casdoor:8000", Map.of(
                        "mfa_satisfied_at", Long.toString(NOW),
                        "permissions", List.of("MFA_SATISFIED"))),
                reg, on, NOW);
        assertThat(names(auth)).filteredOn(s -> s.equals("MFA_SATISFIED")).hasSize(1);
    }
}
