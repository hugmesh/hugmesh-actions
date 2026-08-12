package net.hugmesh.actions.federation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The response body of hugmesh-federation-api's
 * {@code GET /api/federation/username/{candidate}/availability} — the registry
 * owner's authoritative username uniqueness + confusable/reserved verdict.
 *
 * <p>This is the federation stack's CONFUSABLE engine speaking: federation
 * owns the skeleton index (the recomputable look-alike cache) plus the
 * canonical reserved-skeleton set, so it can flag an exact duplicate, a
 * homoglyph/look-alike of an existing username, OR a reserved-label collision
 * that the caller's purely-exact local {@link net.hugmesh.actions.registration.ReservedNames}
 * fast-path would miss (e.g. {@code 4dmin}/{@code akk0ma}).
 *
 * <ul>
 *   <li>{@code available} — true when the candidate can be minted.</li>
 *   <li>{@code collide} — true when the candidate must be REJECTED. The gate in
 *       {@code RegistrationController} keys off this single boolean.</li>
 *   <li>{@code reason} — {@code exact} | {@code confusable} | {@code reserved}
 *       (null/absent when available); surfaced in the 422 so the SPA can tell
 *       the user why.</li>
 *   <li>{@code matchedWith} — the existing username / reserved label the
 *       candidate collided with (may be null for a reserved collision the
 *       registry chooses not to disclose).</li>
 *   <li>{@code skeleton} — the computed confusable skeleton (debug/audit only;
 *       never echoed to the end user).</li>
 * </ul>
 *
 * <p>Unknown fields are ignored so the registry can extend the contract
 * (e.g. add an {@code icuVersion}) without breaking this caller.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsernameAvailability(
        boolean available,
        boolean collide,
        String reason,
        String matchedWith,
        String skeleton) {

    /** Defensive default used when the registry returns an empty/odd body. */
    public static UsernameAvailability collideUnknown() {
        return new UsernameAvailability(false, true, "unknown", null, null);
    }
}
