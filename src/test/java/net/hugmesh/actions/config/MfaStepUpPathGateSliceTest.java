package net.hugmesh.actions.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import net.hugmesh.actions.testsupport.TestIssuer;
import net.hugmesh.commons.config.TenancyResolvers;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.commons.security.Roles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The step-up gate on {@code /api/registration/**} — {@code SecurityConfig.stepUp} —
 * driven through the real filter chain with real RS256 tokens from an in-process
 * {@link TestIssuer}. Both values of {@code app.mfa.enabled} are asserted against the same
 * policy code, on both the prod and the dev/test chain.
 */
class MfaStepUpPathGateSliceTest extends SecuritySliceSupport {

    private static final String PATH = "/api/registration/github-org-admin/evaluate";
    private static final String SIGNAL = "X-Step-Up-Required";
    private static final long WINDOW_MILLIS = 600_000L;

    private TestIssuer issuer;
    private WebTestClient mfaOff;
    private WebTestClient mfaOnProd;
    private WebTestClient mfaOnDev;

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer();
        // Two entries so DomainRegistry.isMultiIssuer() holds and the chain takes the
        // resolver path, which has a real offline decoder (see SecurityConfigPathGateSliceTest).
        DomainRegistry registry = new DomainRegistry(issuer.issuerUri(),
                issuer.issuerUri() + "=brandx," + issuer.issuerUri() + "/other=brandy");
        Object probe = new ProbeController();
        mfaOff = mfaSliceClient(registry, provider(resolver(registry, false)), false, true, probe);
        mfaOnProd = mfaSliceClient(registry, provider(resolver(registry, true)), true, true, probe);
        mfaOnDev = mfaSliceClient(registry, provider(resolver(registry, true)), true, false, probe);
    }

    @AfterEach
    void tearDown() {
        issuer.close();
    }

    private static ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver(
            DomainRegistry registry, boolean mfaEnabled) {
        return TenancyResolvers.tenantResolver(new TenancyResourceServerConfig(
                registry, "", "", mfaEnabled, WINDOW_MILLIS));
    }

    private String token(List<String> roles, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("roles", roles.stream().map(r -> Map.of("name", r)).toList());
        claims.put("name", "u");
        claims.putAll(extraClaims);
        return issuer.mintWithClaims("u", claims);
    }

    private String plain(String... roles) {
        return token(List.of(roles), Map.of());
    }

    /** A token whose {@code mfa_satisfied_at} stamp is {@code ageMillis} old. */
    private String steppedUp(long ageMillis, String... roles) {
        return token(List.of(roles), Map.of(Roles.MFA_SATISFIED_AT_CLAIM,
                String.valueOf(System.currentTimeMillis() - ageMillis)));
    }

    private static WebTestClient.ResponseSpec get(WebTestClient c, String path, String token) {
        return c.get().uri(path).header("Authorization", "Bearer " + token).exchange();
    }

    // --- flag OFF: byte-for-byte the pre-existing behaviour --------------------

    @Test
    void mfaDisabled_adminWithoutStamp_stillAllowed() {
        // The converter never emits the marker while dark; an unconditional check would
        // 403 every admin.
        get(mfaOff, PATH, plain("admin")).expectStatus().isOk()
                .expectHeader().doesNotExist(SIGNAL);
    }

    @Test
    void mfaDisabled_userStillForbidden_withoutSignal() {
        get(mfaOff, PATH, plain("user")).expectStatus().isForbidden()
                .expectHeader().doesNotExist(SIGNAL);
    }

    // --- flag ON: the marker is required, on BOTH chains -----------------------

    @Test
    void mfaEnabled_adminWithoutStamp_forbiddenWithSignal_prod() {
        get(mfaOnProd, PATH, plain("admin")).expectStatus().isForbidden()
                .expectHeader().valueEquals(SIGNAL, "X-Actions-StepUp");
    }

    @Test
    void mfaEnabled_adminWithoutStamp_forbiddenWithSignal_dev() {
        get(mfaOnDev, PATH, plain("admin")).expectStatus().isForbidden()
                .expectHeader().valueEquals(SIGNAL, "X-Actions-StepUp");
    }

    @Test
    void mfaEnabled_adminWithFreshStamp_allowed() {
        get(mfaOnProd, PATH, steppedUp(1_000L, "admin")).expectStatus().isOk()
                .expectHeader().doesNotExist(SIGNAL);
        get(mfaOnDev, PATH, steppedUp(1_000L, "admin")).expectStatus().isOk();
    }

    @Test
    void mfaEnabled_adminWithStaleStamp_forbiddenWithSignal() {
        get(mfaOnProd, PATH, steppedUp(WINDOW_MILLIS + 60_000L, "admin"))
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(SIGNAL, "X-Actions-StepUp");
    }

    @Test
    void mfaEnabled_forgedMarkerPermission_isNotASecondFactor() {
        // MFA_SATISFIED is a reserved converter prefix: a permissions claim cannot mint it.
        String forged = token(List.of("admin"), Map.of("permissions", List.of(Roles.MFA_SATISFIED)));
        get(mfaOnProd, PATH, forged).expectStatus().isForbidden()
                .expectHeader().valueEquals(SIGNAL, "X-Actions-StepUp");
    }

    // --- flag ON: a role denial is NOT a step-up denial -------------------------

    @Test
    void mfaEnabled_userWithFreshStamp_forbiddenWithoutSignal() {
        // A fresh stamp does not substitute for the role: the step-up leg only narrows.
        get(mfaOnProd, PATH, steppedUp(1_000L, "user")).expectStatus().isForbidden()
                .expectHeader().doesNotExist(SIGNAL);
    }

    @Test
    void mfaEnabled_userWithoutStamp_forbiddenWithoutSignal() {
        get(mfaOnProd, PATH, plain("user")).expectStatus().isForbidden()
                .expectHeader().doesNotExist(SIGNAL);
    }

    @Test
    void mfaEnabled_gameMasterWithFreshStamp_forbiddenWithoutSignal() {
        get(mfaOnProd, PATH, steppedUp(1_000L, "game-master")).expectStatus().isForbidden()
                .expectHeader().doesNotExist(SIGNAL);
    }

    @Test
    void mfaEnabled_anonymous_unauthorizedWithoutSignal() {
        mfaOnProd.get().uri(PATH).exchange().expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(SIGNAL);
    }

    @Test
    void mfaEnabled_roleDenial_carriesTheSameChallengeAsWithTheFlagOff() {
        // Installing the refusal handler must not change any non-step-up 403: compare the
        // WWW-Authenticate challenge of a role denial across the two flag values.
        String off = get(mfaOff, PATH, plain("user")).expectStatus().isForbidden()
                .returnResult(String.class).getResponseHeaders().getFirst("WWW-Authenticate");
        String on = get(mfaOnProd, PATH, plain("user")).expectStatus().isForbidden()
                .returnResult(String.class).getResponseHeaders().getFirst("WWW-Authenticate");
        assertThat(off).isNotNull().startsWith("Bearer");
        assertThat(on).isEqualTo(off);
    }

    // --- flag ON: nothing outside /api/registration/** moves --------------------

    @Test
    void mfaEnabled_otherSurfaces_notStepUpGated() {
        get(mfaOnProd, "/api/anything", plain("user")).expectStatus().isOk();
        get(mfaOnProd, "/v3/api-docs", plain("admin")).expectStatus().isOk();
        get(mfaOnProd, "/actuator/metrics", plain("game-master")).expectStatus().isOk();
    }

    @RestController
    static class ProbeController {
        @RequestMapping({PATH, "/api/anything", "/v3/api-docs", "/actuator/metrics"})
        Mono<String> ok() {
            return Mono.just("ok");
        }
    }
}
