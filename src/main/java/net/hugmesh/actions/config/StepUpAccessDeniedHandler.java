package net.hugmesh.actions.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Makes a STEP-UP refusal recognisable on the wire, and changes nothing else.
 *
 * <p>{@link SecurityConfig} composes the registration rule as {@code ROLE_ADMIN AND
 * MFA_SATISFIED} (only when {@code app.mfa.enabled} is on). A denial from either half reaches
 * the chain's access-denied handler as the same bare {@link AccessDeniedException}, so
 * {@code SecurityConfig} records, on the exchange and only after the role half has granted,
 * that the marker half is what refused ({@link #recordDenial}). This handler then answers
 * {@code 403} with {@value #SIGNAL_HEADER}: {@value #FLAVOUR}. Every other denial is
 * delegated to {@link BearerTokenServerAccessDeniedHandler}, which is what Spring installs on
 * a chain that configures {@code oauth2ResourceServer}.
 *
 * <p>The attribute is only ever set server-side, so nothing a client sends can fake the
 * signal, and a lost attribute degrades to the ordinary 403.
 */
final class StepUpAccessDeniedHandler implements ServerAccessDeniedHandler {

    /** The platform's step-up signal header (same spelling as the other BFFs). */
    static final String SIGNAL_HEADER = "X-Step-Up-Required";

    /** This service's step-up flavour, in the platform's {@code X-<flavour>-StepUp} spelling. */
    static final String FLAVOUR = "X-Actions-StepUp";

    private static final String DENIED_ATTRIBUTE = StepUpAccessDeniedHandler.class.getName() + ".DENIED";

    private static final ServerAccessDeniedHandler DEFAULT = new BearerTokenServerAccessDeniedHandler();

    /** Record that the marker half, and only it, refused this exchange. */
    static void recordDenial(ServerWebExchange exchange) {
        exchange.getAttributes().put(DENIED_ATTRIBUTE, Boolean.TRUE);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        if (Boolean.TRUE.equals(exchange.getAttributes().get(DENIED_ATTRIBUTE))) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().set(SIGNAL_HEADER, FLAVOUR);
            return exchange.getResponse().setComplete();
        }
        return DEFAULT.handle(exchange, denied);
    }
}
