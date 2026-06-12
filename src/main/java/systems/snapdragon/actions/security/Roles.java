package systems.snapdragon.actions.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JWT role extraction utility — MIRROR of snapdragon-gateway's
 * {@code systems.snapdragon.gateway.security.Roles} (kept aligned so a token maps
 * to the same role decisions across every service).
 *
 * <p>Casdoor's token template can emit the {@code roles} claim in two shapes:
 *
 * <ol>
 *   <li>List of strings:  {@code "roles": ["admin", "player"]}</li>
 *   <li>List of role objects (default):
 *       {@code "roles": [{"name": "admin", ...}, {"name": "player", ...}]}</li>
 * </ol>
 *
 * <p>Both shapes are handled. Empty / blank entries are skipped silently so a
 * partially-populated role object doesn't introduce a phantom {@code ROLE_}
 * authority.
 *
 * <p>Static helpers below answer the common "does this principal have role X"
 * question from controller / action code without copying the claim-walk.
 */
public final class Roles {

    private Roles() {}

    /**
     * Returns every role name carried by the JWT, upper-cased and trimmed. Empty
     * list if the claim is missing.
     */
    public static List<String> fromJwt(Jwt jwt) {
        List<String> out = new ArrayList<>();
        if (jwt == null) return out;
        Object claim = jwt.getClaim("roles");
        if (claim instanceof Collection<?> list) {
            for (Object item : list) {
                String name = roleNameOf(item);
                if (!name.isBlank()) out.add(name.toUpperCase());
            }
        }
        return out;
    }

    /** True iff {@code roleName} (case-insensitive) is present in the JWT's roles
     *  claim. */
    public static boolean has(Jwt jwt, String roleName) {
        if (jwt == null || roleName == null) return false;
        String want = roleName.trim().toUpperCase();
        for (String r : fromJwt(jwt)) if (r.equals(want)) return true;
        return false;
    }

    public static boolean isAdmin(Jwt jwt) {
        if (jwt == null) return false;
        if (Boolean.TRUE.equals(jwt.getClaim("isAdmin"))) return true;
        return has(jwt, "ADMIN");
    }

    /** True iff the principal carries the {@code game-master} role
     *  (snapdragon.systems platform admin). Gates infra/health URLs. */
    public static boolean isGameMaster(Jwt jwt) {
        return has(jwt, "GAME-MASTER") || has(jwt, "GAME_MASTER");
    }

    /** True iff the principal carries the infra super-role {@code SUPERADMIN}.
     *  The ONLY role exempt from per-domain scoping — SUPERADMIN reaches every
     *  surface in every brand (the infra control plane). Both hyphen and underscore
     *  spellings of the Casdoor role name match. */
    public static boolean isSuperadmin(Jwt jwt) {
        return has(jwt, "SUPERADMIN") || has(jwt, "SUPER-ADMIN") || has(jwt, "SUPER_ADMIN");
    }

    /** True iff the principal may build mesh-WORKLOAD images. Granted to trusted
     *  per-domain admins as a separate capability (NOT an admin default);
     *  SUPERADMIN reaches the build surface via the cross-domain bypass. Both hyphen
     *  and underscore spellings match (the converter upper-cases the raw Casdoor
     *  role name without stripping its separator). */
    public static boolean isImageBuilder(Jwt jwt) {
        return has(jwt, "IMAGE-BUILDER") || has(jwt, "IMAGE_BUILDER");
    }

    /** The brand/domain this principal is scoped to, derived from the token ISSUER
     *  (per-brand Casdoor =&gt; distinct {@code iss}) through the SAME registry the
     *  converter uses. Returns {@code "default"} for the single-issuer fallback so
     *  single-issuer clusters keep one domain, and (defensively) when
     *  {@code jwt}/{@code registry} is null. A {@code null} from the registry
     *  (unknown issuer in multi-brand mode) is surfaced as-is so the caller can fail
     *  closed. */
    public static String domainOf(Jwt jwt, DomainRegistry registry) {
        if (jwt == null || registry == null) return DomainRegistry.DEFAULT_DOMAIN;
        return registry.domainOf(jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
    }

    /** Domain carried on the already-converted authorities ({@code DOMAIN_<x>}) —
     *  the form a caller holding only Spring authorities (no registry) can read.
     *
     *  <p>FAIL-CLOSED on an ambiguous authority set: the canonical converter
     *  ({@code CasdoorAuthorities}) stamps EXACTLY ONE issuer-derived
     *  {@code DOMAIN_<x>}, so any other count is anomalous —
     *  <ul>
     *    <li>ZERO {@code DOMAIN_} authorities (a hand-built / pre-converter token) —
     *        we can't prove a tenant, so return {@code null}.</li>
     *    <li>MORE THAN ONE {@code DOMAIN_} authority — an INJECTION signature (e.g.
     *        a forged {@code permissions:["DOMAIN_brandy"]} alongside the genuine
     *        issuer {@code DOMAIN_brandx}); never pick the first, return
     *        {@code null}.</li>
     *  </ul>
     *  Belt-and-suspenders: the converter's reserved-prefix guard already prevents
     *  the injected DOMAIN_ from ever appearing, and a {@link Jwt}-holding caller
     *  derives the principal domain from the validated issuer (not this list) — this
     *  overload simply refuses to be a spoofable side channel. Prefer
     *  {@link #domainOf(Jwt, DomainRegistry)} (issuer-keyed) where a {@link Jwt} is
     *  in hand. */
    public static String domainOf(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) return null;                       // fail-closed
        String found = null;
        for (GrantedAuthority a : authorities) {
            String s = a.getAuthority();
            if (s != null && s.startsWith("DOMAIN_")) {
                if (found != null) return null;                     // >1 DOMAIN_ -> injection, fail-closed
                found = s.substring("DOMAIN_".length());
            }
        }
        return found;                                               // null when ZERO DOMAIN_ -> fail-closed
    }

    private static String roleNameOf(Object item) {
        if (item == null) return "";
        if (item instanceof String s) return s.trim();
        if (item instanceof Map<?, ?> m) {
            Object name = m.get("name");
            if (name instanceof String s && !s.isBlank()) return s.trim();
        }
        return "";
    }

    /** Casdoor user id (subject) accessor — the single place we choose between
     *  {@code sub} and {@code id} so a Casdoor schema flip doesn't ripple through
     *  caller code. */
    public static String casdoorIdOf(Jwt jwt) {
        if (jwt == null) return null;
        String sub = jwt.getSubject();
        if (sub != null && !sub.isBlank()) return sub;
        Object id = jwt.getClaim("id");
        return id == null ? null : id.toString();
    }

    /** Lowercase username from the {@code name} claim. */
    public static String usernameOf(Jwt jwt) {
        if (jwt == null) return null;
        Object n = jwt.getClaim("name");
        return n == null ? null : n.toString();
    }

    /** Convert {@code Collection<GrantedAuthority>} → role-name list (strips the
     *  {@code ROLE_} prefix). Useful for log lines / debug. */
    public static List<String> namesOf(Collection<? extends GrantedAuthority> authorities) {
        List<String> out = new ArrayList<>();
        if (authorities == null) return out;
        for (GrantedAuthority a : authorities) {
            String s = a.getAuthority();
            if (s == null) continue;
            out.add(s.startsWith("ROLE_") ? s.substring(5) : s);
        }
        return out;
    }
}
