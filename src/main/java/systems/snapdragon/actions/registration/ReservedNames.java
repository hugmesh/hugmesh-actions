package systems.snapdragon.actions.registration;

import java.text.Normalizer;
import java.util.Set;

/**
 * The platform-wide RESERVED USERNAME / PREFIX list and the username
 * normaliser used to enforce it.
 *
 * <p>A username must never equal a label that is also a dedicated app
 * path/prefix route or a reserved subdomain, otherwise the freshly
 * created user's vanity surface (e.g. {@code <user>.snapdragon.systems}
 * or {@code /user/<user>}) would shadow — or be shadowed by — a gateway
 * route. We reject such usernames at the FIRST point the platform sees
 * them (registration) and again at the admin/provision layer.
 *
 * <p><b>CANONICAL SOURCE.</b> This list is the union of, and MUST stay in
 * lockstep with, the gateway route prefixes defined in
 * {@code snapdragon-gateway} —
 * {@code src/main/java/systems/snapdragon/gateway/cache/CanonicalSeed.java}
 * (mirrored in {@code cluster/manifests/route-access/schema.sql} and
 * {@code snapdragon-gateway/compose/route-access-seed.sql}). Concretely it
 * is the union of:
 *
 * <ul>
 *   <li>fe-gateway route prefixes: admin, apidocs, be, bff, _fragments,
 *       fragments, login, mod, user;</li>
 *   <li>be-gateway route prefixes: api, _matrix, oauth;</li>
 *   <li>reserved subdomains: id, api, matrix, mastodon, scale, headscale,
 *       www;</li>
 *   <li>the snapdragon.games app prefixes: games, play;</li>
 *   <li>defensive web/infra names (root, static, assets, public, health,
 *       healthz, actuator, metrics, well-known, favicon, robots, sitemap,
 *       status, support, help, social, gm, pki, vault, argocd, gitlab,
 *       registry, logout, users, auth).</li>
 * </ul>
 *
 * <p>The SAME canonical list is duplicated verbatim in
 * {@code snapdragon-admin-bff-api} ({@code ReservedNames}); the two copies
 * MUST be kept identical. When a new dedicated route prefix is added to the
 * gateway, add its first path segment here (and there).
 */
public final class ReservedNames {

    private ReservedNames() {}

    /**
     * Canonical reserved labels — case-insensitive. A username (after
     * {@link #normalize(String)}) must not equal any of these. Keep this
     * block byte-for-byte identical to the copy in snapdragon-admin-bff-api.
     */
    public static final Set<String> RESERVED = Set.of(
            // fe-gateway route prefixes
            "admin", "apidocs", "be", "bff", "_fragments", "fragments",
            "login", "logout", "mod", "user", "users",
            // be-gateway route prefixes
            "api", "_matrix", "matrix", "mastodon", "pleroma", "oauth", "oauth2",
            // reserved subdomains
            "id", "auth", "scale", "headscale", "www",
            // defensive web / infra names
            "root", "static", "assets", "public", "health", "healthz",
            "actuator", "metrics", "well-known", "favicon", "robots",
            "sitemap", "status", "support", "help", "social", "gm",
            "pki", "vault", "argocd", "gitlab", "registry",
            // snapdragon.games app prefixes
            "games", "play");

    /**
     * Normalise a candidate username to the DNS-label form the vanity
     * subdomain uses, so a reserved label cannot be smuggled past the check
     * with case/Unicode/separator tricks:
     *
     * <ul>
     *   <li>Unicode NFKC fold (so full-width / compatibility forms collapse
     *       onto their ASCII equivalents);</li>
     *   <li>trim + lower-case (the check is case-insensitive);</li>
     *   <li>collapse any run of non-{@code [a-z0-9]} characters to a single
     *       {@code -} (mirrors how the vanity subdomain slugifies a handle —
     *       {@code _matrix}, {@code well.known}, {@code well known} all fold
     *       to the same DNS label) and strip leading/trailing {@code -}.</li>
     * </ul>
     *
     * <p>Returns "" for a null/blank input.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String n = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().toLowerCase();
        // Collapse separators (incl. '_' and '.') to '-' so the label matches
        // the slug a vanity subdomain would derive, then trim stray dashes.
        n = n.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return n;
    }

    /**
     * True if {@code username} (once normalised) collides with a reserved
     * label. Compares both the normalised form AND a dash-stripped form so a
     * leading-underscore route prefix such as {@code _matrix}/{@code _fragments}
     * (which normalises to {@code matrix}/{@code fragments}) is still caught
     * regardless of how the raw label was punctuated.
     */
    public static boolean isReserved(String username) {
        String n = normalize(username);
        if (n.isEmpty()) return false;
        if (RESERVED.contains(n)) return true;
        // The underscore-prefixed prefixes (_matrix, _fragments) normalise to
        // their dash-free core; compare that against the reserved set too.
        String collapsed = n.replace("-", "");
        return RESERVED.contains(collapsed);
    }
}
