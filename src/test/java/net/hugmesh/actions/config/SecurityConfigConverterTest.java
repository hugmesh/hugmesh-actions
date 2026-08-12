package net.hugmesh.actions.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import net.hugmesh.commons.security.CasdoorAuthorities;
import net.hugmesh.commons.security.DomainRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the Casdoor-claim -&gt; Spring-authority mapping
 * ({@link SecurityConfig#casdoorAuthoritiesConverter()} delegating to
 * {@link CasdoorAuthorities}), exercised directly against synthetic JWTs (no
 * JWKS / decoder needed).
 *
 * <p>The converter now also stamps an issuer-derived {@code DOMAIN_<x>} authority,
 * so a {@link DomainRegistry} is supplied. With NO multi-issuer config the registry
 * is in single-issuer mode and every token gets {@code DOMAIN_default} — this test
 * uses a no-config registry so the {@code DOMAIN_default} floor accompanies every
 * principal here.
 */
class SecurityConfigConverterTest {

    /** No-config registry: empty issuer-uri / issuers -> single-issuer mode,
     *  so {@code domainOf(anything)} is {@code "default"}. */
    private static final DomainRegistry REGISTRY = new DomainRegistry("", "");

    private static final String DOMAIN_DEFAULT = "DOMAIN_default";

    private final Converter<Jwt, Flux<GrantedAuthority>> converter =
            CasdoorAuthorities.converter(REGISTRY);

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
    void everyPrincipalGetsRoleUserFloorAndDomain() {
        List<String> auths = authoritiesOf(jwtWith(Map.of()));
        assertThat(auths).containsExactly("ROLE_USER", DOMAIN_DEFAULT);
    }

    @Test
    void stringRolesBecomeUppercaseRolePrefixed() {
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "roles", List.of("game-master", "editor"))));
        assertThat(auths)
                .contains("ROLE_USER", "ROLE_GAME-MASTER", "ROLE_EDITOR", DOMAIN_DEFAULT);
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
                "ROLE_USER", "ROLE_GAME-MASTER", "metrics:read", "ROLE_ADMIN", DOMAIN_DEFAULT);
    }

    @Test
    void permissionsCannotForgeRoleAuthority() {
        // A user-influenced permission string starting with the reserved ROLE_
        // prefix must be DROPPED — it can never become a ROLE_* authority.
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "permissions", List.of("ROLE_SUPERADMIN", "ROLE_ADMIN", "metrics:read"))));
        assertThat(auths).doesNotContain("ROLE_SUPERADMIN", "ROLE_ADMIN");
        assertThat(auths).contains("metrics:read");          // the benign one survives
    }

    @Test
    void permissionsCannotForgeDomainAuthority() {
        // A forged permissions:["DOMAIN_other"] must be DROPPED; the ONLY DOMAIN_
        // authority is the issuer-derived one (DOMAIN_default here).
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "permissions", List.of("DOMAIN_other", "DOMAIN_brandy"))));
        assertThat(auths).doesNotContain("DOMAIN_other", "DOMAIN_brandy");
        assertThat(auths).contains(DOMAIN_DEFAULT);
        assertThat(auths).filteredOn(a -> a.startsWith("DOMAIN_")).containsExactly(DOMAIN_DEFAULT);
    }

    @Test
    void reservedGuardIsCaseAndWhitespaceInsensitive() {
        // Lower/mixed case + surrounding whitespace must still be caught.
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "permissions", List.of(" role_admin ", "domain_x", "Role_SuperAdmin"))));
        assertThat(auths).filteredOn(a -> a.toUpperCase().startsWith("ROLE_"))
                .containsExactly("ROLE_USER");
        assertThat(auths).filteredOn(a -> a.startsWith("DOMAIN_")).containsExactly(DOMAIN_DEFAULT);
    }

    @Test
    void rolesEntryNamedDomainBecomesInertRolePrefixed() {
        // A roles entry "DOMAIN_x" gains the ROLE_ prefix first -> ROLE_DOMAIN_X,
        // an inert non-tenant authority; it can NEVER forge a DOMAIN_ scope.
        List<String> auths = authoritiesOf(jwtWith(Map.of(
                "roles", List.of("DOMAIN_brandy"))));
        assertThat(auths).contains("ROLE_DOMAIN_BRANDY");
        assertThat(auths).filteredOn(a -> a.startsWith("DOMAIN_")).containsExactly(DOMAIN_DEFAULT);
    }
}
