package systems.snapdragon.actions.registration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the canonical reserved-username list + normaliser.
 * The same list (and the same expectations) are mirrored in
 * snapdragon-admin-bff-api's {@code ReservedNamesTest}.
 */
class ReservedNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "admin", "apidocs", "be", "bff", "fragments", "login", "logout",
            "mod", "user", "users", "api", "matrix", "mastodon", "pleroma", "forum",
            "oauth", "oauth2", "id", "auth", "scale", "headscale", "www", "root",
            "static", "assets", "public", "health", "healthz", "actuator",
            "metrics", "well-known", "favicon", "robots", "sitemap", "status",
            "support", "help", "social", "gm", "pki", "vault", "argocd",
            "gitlab", "registry", "games", "play"})
    void everyCanonicalLabelIsReserved(String label) {
        assertThat(ReservedNames.isReserved(label)).isTrue();
    }

    /**
     * The 14 labels backported to reach the canonical 61-label list (parity with
     * snapdragon-federation-api / snapdragon-user-bff-api). These are the
     * snapdragon.GAMES app route prefixes the actions copy was previously missing,
     * so a user could register e.g. {@code publisher} or {@code games-vn-maker}
     * even though {@code profile-<that>} would be rejected at the federation DB.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "api-docs", "banned", "docs", "games-dodecahedron", "games-rpg-maker",
            "games-three-wizards", "games-vn-maker", "producer",
            "producer-registration", "publisher", "registration", "_remotes",
            "wasm", "wasm-fragment"})
    void everyBackportedGamesPrefixIsReserved(String label) {
        assertThat(ReservedNames.isReserved(label)).isTrue();
    }

    /**
     * The optional-service / games-console route prefixes added for parity with
     * the canonical source (reserved-prefixes.yaml): {@code tube} (LocalTube
     * {@code /tube/**} route) and {@code games-instances-console} (the per-instance
     * game-server console dispatcher Service the {@code /games/servers/**} route
     * targets). A user must not be able to register either handle.
     */
    @ParameterizedTest
    @ValueSource(strings = {"tube", "games-instances-console"})
    void everyOptionalServicePrefixIsReserved(String label) {
        assertThat(ReservedNames.isReserved(label)).isTrue();
    }

    /**
     * The 6 labels added to reconcile this copy with the canonical
     * reserved-prefixes.yaml (drift fix): {@code superadmin} (the TAILNET
     * superadmin App-Store console {@code /superadmin/appstore/**} route),
     * {@code _synapse} + {@code media} (the publicly-routable homeserver client
     * surfaces {@code /_synapse/**} and {@code /media/**}), {@code phpbb} (the
     * phpBB forum forward-reserve), and {@code owncast} + {@code wiki} (the
     * optional video/wiki services, forward-reserved like forum/phpbb). A user
     * must not be able to register any of these handles.
     */
    @ParameterizedTest
    @ValueSource(strings = {"superadmin", "_synapse", "media", "phpbb", "owncast", "wiki"})
    void everyReconciledCanonicalLabelIsReserved(String label) {
        assertThat(ReservedNames.isReserved(label)).isTrue();
        assertThat(ReservedNames.RESERVED).contains(label);
    }

    /**
     * The 7 labels backported to reach the canonical 77-label list (canonical
     * revisions 4-6 of reserved-prefixes.yaml): {@code character-studio} (the
     * Character Studio VN micro-frontend {@code /character-studio/**} fe-gateway
     * route) and {@code v1} (the edge-AI analysis API {@code /v1/analyze/**}
     * be-gateway route, canonical revision 4); {@code index.html},
     * {@code favicon.ico}, {@code importmap.json}, {@code remoteentry.json} (the
     * fe-gateway webroot static / module-federation manifest filenames, distinct
     * literal first-segments from the already-reserved bare {@code favicon},
     * canonical revision 5); and {@code ai} (the edge-AI inference tier's own
     * reserved host {@code ai.snapdragon.systems}, canonical revision 6). A user
     * must not be able to register any of these handles. {@code remoteentry.json}
     * is stored lower-case (like every entry in this set) though the on-disk
     * filename is {@code remoteEntry.json} — case-insensitive matching for that
     * exact mixed-case literal is covered separately below.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "character-studio", "v1", "index.html", "favicon.ico",
            "importmap.json", "remoteentry.json", "ai"})
    void everyCanonicalRevision4To6LabelIsReserved(String label) {
        assertThat(ReservedNames.isReserved(label)).isTrue();
        assertThat(ReservedNames.RESERVED).contains(label);
    }

    @Test
    void reservedSetIsExactlyTheCanonical77Labels() {
        // Guards against silent drift from the canonical reserved-prefixes.yaml
        // (and the federation/user-bff RESERVED_LABELS copies).
        // 62 -> 64: added `tube` + `games-instances-console`.
        // 64 -> 70: reconciled with the canonical YAML — added `superadmin`,
        // `_synapse`, `media`, `phpbb`, `owncast`, `wiki` (canonical revision 3).
        // 70 -> 77: reconciled with the canonical YAML — added `character-studio`,
        // `v1` (revision 4), `index.html`, `favicon.ico`, `importmap.json`,
        // `remoteentry.json` (revision 5), and `ai` (revision 6).
        assertThat(ReservedNames.RESERVED).hasSize(77);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // _remotes normalises to "remotes" (no bare core in the set); the
            // underscore-re-prefix compare must still catch it. Punctuation/case
            // variants of the backported labels fold onto the reserved label.
            "_remotes", "_Remotes", "WASM-FRAGMENT", "wasm_fragment",
            "Games VN Maker", "producer.registration", "API_DOCS"})
    void backportedPrefixSeparatorAndCaseVariantsAreReserved(String label) {
        assertThat(ReservedNames.isReserved(label)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ADMIN", "Admin", "  Login  ", "GAMES", "Api",
            // remoteEntry.json is the actual mixed-case on-disk filename (stored
            // lower-case as "remoteentry.json" in RESERVED); confirm the
            // case-insensitive raw-form compare in isReserved() still catches it.
            "remoteEntry.json", "RemoteEntry.JSON"})
    void reservedCheckIsCaseInsensitiveAndTrimmed(String label) {
        assertThat(ReservedNames.isReserved(label)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "_matrix", "_fragments", "well.known", "well known", "ad-min", "a.d.m.i.n"})
    void underscoreAndSeparatorVariantsNormaliseOntoReservedLabels(String label) {
        // _matrix -> matrix, _fragments -> fragments, well.known -> well-known,
        // ad-min/a.d.m.i.n collapse to admin via the dash-stripped compare.
        assertThat(ReservedNames.isReserved(label)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "magus", "alice", "bob42", "game-master", "admin-helper",
            "apiary", "username", "logan", "playful", "matrixx", "ider"})
    void ordinaryUsernamesAreNotReserved(String label) {
        assertThat(ReservedNames.isReserved(label)).isFalse();
    }

    @Test
    void nullAndBlankAreNotReserved() {
        assertThat(ReservedNames.isReserved(null)).isFalse();
        assertThat(ReservedNames.isReserved("")).isFalse();
        assertThat(ReservedNames.isReserved("   ")).isFalse();
    }

    @Test
    void normalizeFoldsToDnsLabelForm() {
        assertThat(ReservedNames.normalize("  Admin  ")).isEqualTo("admin");
        assertThat(ReservedNames.normalize("_matrix")).isEqualTo("matrix");
        assertThat(ReservedNames.normalize("well.known")).isEqualTo("well-known");
        assertThat(ReservedNames.normalize("Game_Master")).isEqualTo("game-master");
    }
}
