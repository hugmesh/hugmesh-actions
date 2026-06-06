package systems.snapdragon.actions.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link SecurityConfig#casdoorAuthoritiesConverter()} —
 * the Casdoor-claim -> Spring-authority mapping, exercised directly
 * against synthetic JWTs (no JWKS / decoder needed).
 */
class SecurityConfigConverterTest {

    private final Converter<Jwt, Flux<GrantedAuthority>> converter =
            SecurityConfig.casdoorAuthoritiesConverter();

    private static Jwt jwtWith(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("alice");
        claims.forEach(b::claim);
        // ensure at least one claim so the builder is valid
        b.claim("sub", "alice");
        return b.build();
    }

    private List<String> authoritiesOf(Jwt jwt) {
        return converter.convert(jwt)
                .map(GrantedAuthority::getAuthority)
                .collectList()
                .block();
    }

    @Test
    void everyPrincipalGetsRoleUserFloor() {
        List<String> auths = authoritiesOf(jwtWith(Map.of()));
        assertThat(auths).containsExactly("ROLE_USER");
    }

    @Test
    void stringRolesBecomeUppercaseRolePrefixed() {
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "roles", List.of("game-master", "editor"))));
        assertThat(auths)
                .contains("ROLE_USER", "ROLE_GAME-MASTER", "ROLE_EDITOR");
    }

    @Test
    void objectRolesUseNameField() {
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "roles", List.of(Map.of("name", "admin"), Map.of("name", "  ")))));
        // admin -> ROLE_ADMIN; the blank-name object is skipped
        assertThat(auths).contains("ROLE_ADMIN");
        assertThat(auths).doesNotContain("ROLE_");
    }

    @Test
    void permissionsAddedVerbatim() {
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "permissions", List.of("registration:write", "metrics:read"))));
        assertThat(auths).contains("registration:write", "metrics:read");
    }

    @Test
    void isAdminTrueAddsRoleAdmin() {
        List<String> auths = authoritiesOf(jwtWith(Map.of("isAdmin", true)));
        assertThat(auths).contains("ROLE_ADMIN");
    }

    @Test
    void isAdminFalseDoesNotAddRoleAdmin() {
        List<String> auths = authoritiesOf(jwtWith(Map.of("isAdmin", false)));
        assertThat(auths).doesNotContain("ROLE_ADMIN");
    }

    @Test
    void rolesPermissionsAndIsAdminCompose() {
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "roles", List.of("game-master"),
                "permissions", List.of("metrics:read"),
                "isAdmin", true)));
        assertThat(auths).contains(
                "ROLE_USER", "ROLE_GAME-MASTER", "metrics:read", "ROLE_ADMIN");
    }
}
