package systems.hugmesh.actions.registration;

import java.text.Normalizer;
import java.util.Set;

/**
 * The platform-wide RESERVED USERNAME / PREFIX list and the username
 * normaliser used to enforce it.
 *
 * <p>A username must never equal a label that is also a dedicated app
 * path/prefix route or a reserved subdomain, otherwise the freshly
 * created user's vanity surface (e.g. {@code <user>.hugmesh.systems}
 * or {@code /user/<user>}) would shadow — or be shadowed by — a gateway
 * route. We reject such usernames at the FIRST point the platform sees
 * them (registration) and again at the admin/provision layer.
 *
 * <p><b>CANONICAL SOURCE.</b> This list is the union of, and MUST stay in
 * lockstep with, the gateway route prefixes defined in
 * {@code hugmesh-gateway} —
 * {@code src/main/java/systems/hugmesh/gateway/cache/CanonicalSeed.java}
 * (mirrored in {@code cluster/manifests/route-access/schema.sql} and
 * {@code hugmesh-gateway/compose/route-access-seed.sql}). Concretely it
 * is the union of:
 *
 * <ul>
 *   <li>fe-gateway route prefixes: admin, apidocs, be, bff, _fragments,
 *       _remotes, fragments, login, logout, mod, user, users;</li>
 *   <li>be-gateway route prefixes: api, _matrix, matrix, mastodon, pleroma,
 *       forum, oauth, oauth2;</li>
 *   <li>reserved subdomains: id, auth, scale, headscale, www;</li>
 *   <li>the hugmesh.games app route prefixes (derived from the games
 *       front-end nginx {@code /_remotes/<slug>/} blocks + location
 *       prefixes + the {@code <service>.hugmesh.games} subdomains):
 *       games, play, wasm, wasm-fragment, producer, producer-registration,
 *       publisher, registration, banned, api-docs, docs,
 *       games-dodecahedron, games-rpg-maker, games-three-wizards,
 *       games-vn-maker;</li>
 *   <li>defensive web/infra names (root, static, assets, public, health,
 *       healthz, actuator, metrics, well-known, favicon, robots, sitemap,
 *       status, support, help, social, gm, pki, vault, argocd, gitlab,
 *       registry).</li>
 * </ul>
 *
 * <p>The SAME canonical list is duplicated verbatim in
 * {@code hugmesh-admin-bff-api} ({@code ReservedNames}); the two copies
 * MUST be kept identical. When a new dedicated route prefix is added to the
 * gateway, add its first path segment here (and there).
 *
 * <p>TODO: this is one of 3 independent hand-synced physical copies of the
 * canonical {@code specs/cluster/reserved-prefixes.yaml} list across the
 * platform (this repo, {@code hugmesh-federation-api}, and the shared
 * {@code hugmesh-auth-core} library consumed by user-bff/admin-bff) — a
 * missed sync is a real reserved-username enforcement gap (happened once
 * already; fixed in #42). A durable fix is to generate this set from the
 * canonical YAML at build time instead of hand-copying it. Cross-repo,
 * not a quick local patch — not done here.
 */
public final class ReservedNames {

    private ReservedNames() {}

    /**
     * Canonical reserved labels — case-insensitive. A username (after
     * {@link #normalize(String)}) must not equal any of these. Keep this
     * block byte-for-byte identical to the copy in hugmesh-admin-bff-api.
     */
    public static final Set<String> RESERVED = Set.of(
            // fe-gateway route prefixes
            "admin", "apidocs", "be", "bff", "_fragments", "_remotes", "fragments",
            "login", "logout", "mod", "user", "users",
            // superadmin: TAILNET superadmin App-Store console route prefix
            // (/superadmin/appstore/** on the superadmin-gateway)
            "superadmin",
            // character-studio: the Character Studio (VN) micro-frontend remote,
            // served at /character-studio/** on the fe-gateway
            // (apps/20-gateway-fe.yaml route[character-studio-ui].path)
            "character-studio",
            // be-gateway route prefixes + publicly-routable homeserver surfaces
            // (_synapse: /_synapse/** Matrix Synapse-compat; media: /media/** Mastodon)
            "api", "_matrix", "matrix", "_synapse", "media", "mastodon", "pleroma",
            "forum", "phpbb", "oauth", "oauth2",
            // edge-AI routes: ai.hugmesh.systems (the edge-AI inference tier
            // host, route[edge-ai].host) + /v1/analyze/** (the edge-AI analysis
            // API path, route[edge-ai-analysis].path, both apps/20-gateway-be.yaml)
            "ai", "v1",
            // optional-service route prefixes / reserved subdomains: LocalTube
            // (/tube/content/** route-access seed) + the optional video/wiki services
            // (owncast/wiki ride /bff/** + /social/wiki/**; reserved so a vanity
            // owncast./wiki. handle can never collide)
            "tube", "owncast", "wiki",
            // reserved subdomains
            "id", "auth", "scale", "headscale", "www",
            // defensive web / infra names
            "root", "static", "assets", "public", "health", "healthz",
            "actuator", "metrics", "well-known", "favicon", "robots",
            "sitemap", "status", "support", "help", "social", "gm",
            "pki", "vault", "argocd", "gitlab", "registry",
            // FE webroot static / module-federation manifest filenames (fe-gateway
            // anonymous-SPA-shell rows, distinct literal first-segments from the
            // already-reserved bare "favicon" label above). Stored lower-case
            // (like every other entry here) though the canonical spec/on-disk
            // filename is "remoteEntry.json" — this set's match is documented
            // case-insensitive and isReserved() always lower-cases the candidate.
            "index.html", "favicon.ico", "importmap.json", "remoteentry.json",
            // hugmesh.GAMES app route prefixes (derived: nginx /_remotes/<slug>/
            // + location prefixes + <service>.hugmesh.games subdomains)
            "games", "play", "wasm", "wasm-fragment", "producer",
            "producer-registration", "publisher", "registration", "banned",
            "api-docs", "docs", "games-dodecahedron", "games-rpg-maker",
            "games-three-wizards", "games-vn-maker", "games-instances-console");

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
     * label. Compares the normalised form, a dash-stripped form AND an
     * underscore-re-prefixed form, so a leading-underscore route prefix such as
     * {@code _matrix}/{@code _fragments}/{@code _remotes} (which normalises to
     * {@code matrix}/{@code fragments}/{@code remotes}) is still caught
     * regardless of how the raw label was punctuated. {@code _matrix} and
     * {@code _fragments} also have bare cores ({@code matrix}/{@code fragments})
     * in the set; {@code _remotes} does not, so the underscore-re-prefixed
     * compare is what catches it. Also compares the raw trimmed/lower-cased
     * form BEFORE normalisation, so an exact dotted literal — {@code
     * index.html}, {@code favicon.ico}, {@code importmap.json}, {@code
     * remoteEntry.json} — still matches; {@link #normalize(String)} collapses
     * the {@code .} to {@code -} like any other separator, so the normalised
     * form alone would never equal one of those literal reserved entries.
     */
    public static boolean isReserved(String username) {
        if (username == null) return false;
        String raw = username.trim().toLowerCase();
        if (RESERVED.contains(raw)) return true;
        String n = normalize(username);
        if (n.isEmpty()) return false;
        if (RESERVED.contains(n)) return true;
        // The underscore-prefixed prefixes (_matrix, _fragments) normalise to
        // their dash-free core; compare that against the reserved set too.
        String collapsed = n.replace("-", "");
        if (RESERVED.contains(collapsed)) return true;
        // Re-prefix a leading underscore so an underscore-only reserved label
        // with no bare core (e.g. "_remotes" -> normalises to "remotes") is
        // still caught after normalisation stripped the underscore.
        return RESERVED.contains("_" + n) || RESERVED.contains("_" + collapsed);
    }
}
