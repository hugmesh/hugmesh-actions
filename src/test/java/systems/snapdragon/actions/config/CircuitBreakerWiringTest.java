package systems.snapdragon.actions.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression guard for the resilience4j circuit-breaker wiring that the
 * Spring Boot 3-&gt;4 migration nearly lost silently.
 *
 * <p>This service relies on {@code @CircuitBreaker} at six call sites
 * (OidcRoleHookClient, FederationUsernameClient, GameMasterAdminSyncAction,
 * OidcDefaultRoleProbeAction, GitHubOrgAdminGrantAction,
 * GitHubOrgAdminReconcileAction) — several with {@code fail-closed} /
 * {@code fail-open} fallbacks that are part of the security posture.
 *
 * <p>The breaker is applied by a Spring AOP {@code @Aspect}
 * ({@code io.github.resilience4j.spring6.circuitbreaker.configure
 * .CircuitBreakerAspect}), which resilience4j's auto-configuration only
 * registers when AspectJ is on the classpath (its
 * {@code AspectJOnClasspathCondition}). Crucially, the
 * {@code resilience4j-spring-boot4} starter does NOT pull
 * {@code spring-boot-starter-aop} transitively — so if that explicit
 * dependency is ever dropped from {@code pom.xml}, the aspect is never
 * registered and EVERY {@code @CircuitBreaker} silently becomes a no-op
 * (the fallbacks never fire). The compile and the rest of the suite stay
 * green, so nothing else would catch it.
 *
 * <p>This test fails fast in exactly that case by asserting the AspectJ
 * marker type the resilience4j condition looks for is loadable at test
 * runtime — i.e. {@code spring-boot-starter-aop} is on the classpath.
 */
class CircuitBreakerWiringTest {

    /**
     * The exact class resilience4j's {@code AspectJOnClasspathCondition}
     * checks for. Present iff the {@code aspectjweaver} dependency is on
     * the classpath; its absence is what makes {@code @CircuitBreaker} a
     * silent no-op under Boot 4 (which removed the old
     * {@code spring-boot-starter-aop} convenience starter).
     */
    private static final String ASPECTJ_MARKER =
            "org.aspectj.lang.ProceedingJoinPoint";

    @Test
    void aspectJOnClasspath_soResilience4jCircuitBreakerActuallyWires() {
        // If this throws, spring-boot-starter-aop was dropped and every
        // @CircuitBreaker (incl. the fail-closed/open fallbacks) is inert.
        assertThatCode(() -> Class.forName(ASPECTJ_MARKER))
                .as("spring-boot-starter-aop (AspectJ) MUST be on the "
                        + "classpath, or resilience4j never registers the "
                        + "CircuitBreakerAspect and every @CircuitBreaker is "
                        + "a silent no-op under Spring Boot 4")
                .doesNotThrowAnyException();
    }
}
