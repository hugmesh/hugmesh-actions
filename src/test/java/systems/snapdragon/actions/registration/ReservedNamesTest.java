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
            "mod", "user", "users", "api", "matrix", "mastodon", "pleroma",
            "oauth", "oauth2", "id", "auth", "scale", "headscale", "www", "root",
            "static", "assets", "public", "health", "healthz", "actuator",
            "metrics", "well-known", "favicon", "robots", "sitemap", "status",
            "support", "help", "social", "gm", "pki", "vault", "argocd",
            "gitlab", "registry", "games", "play"})
    void everyCanonicalLabelIsReserved(String label) {
        assertThat(ReservedNames.isReserved(label)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ADMIN", "Admin", "  Login  ", "GAMES", "Api"})
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
