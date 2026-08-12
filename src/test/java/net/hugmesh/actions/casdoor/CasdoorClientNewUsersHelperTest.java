package net.hugmesh.actions.casdoor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for the package-private row-mapping helpers
 * {@link CasdoorClient#toNewUsers(Object)} / {@link CasdoorClient#toNewUser(Object)}
 * extracted out of {@link CasdoorClient#listNewUsers(int)} during the
 * Convention H reactive de-nesting. These pin the EXACT behavior the
 * inline {@code .map} lambda had — tolerance of a missing / non-list
 * {@code data}, dropping of nameless rows, and null-tolerant optional
 * fields — so the extraction is provably behavior-preserving.
 */
class CasdoorClientNewUsersHelperTest {

    @Test
    void toNewUsers_nonList_returnsEmpty() {
        // Missing data (null) and a non-list value both collapse to empty —
        // never an exception.
        assertThat(CasdoorClient.toNewUsers(null)).isEmpty();
        assertThat(CasdoorClient.toNewUsers("not-a-list")).isEmpty();
        assertThat(CasdoorClient.toNewUsers(Map.of("name", "alice"))).isEmpty();
    }

    @Test
    void toNewUsers_mapsRows_inOrder_withOptionalFields() {
        List<Object> rows = List.of(
                Map.of("name", "alice", "signupApplication", "app-google",
                        "createdTime", "2026-06-19T10:00:00Z"),
                Map.of("name", "bob", "signupApplication", "app-github",
                        "createdTime", "2026-06-19T09:00:00Z"));

        List<CasdoorClient.NewUser> users = CasdoorClient.toNewUsers(rows);

        assertThat(users).hasSize(2);
        assertThat(users.get(0)).isEqualTo(
                new CasdoorClient.NewUser("alice", "app-google", "2026-06-19T10:00:00Z"));
        assertThat(users.get(1).name()).isEqualTo("bob");
    }

    @Test
    void toNewUsers_dropsNamelessRows_andToleratesMissingOptionalFields() {
        java.util.List<Object> rows = new java.util.ArrayList<>();
        rows.add(Map.of("signupApplication", "app-google")); // no name -> dropped
        rows.add(Map.of("name", ""));                        // blank name -> dropped
        rows.add("not-a-map");                               // non-map -> dropped
        rows.add(Map.of("name", "carol"));                   // no optional fields -> nulls

        List<CasdoorClient.NewUser> users = CasdoorClient.toNewUsers(rows);

        assertThat(users).hasSize(1);
        assertThat(users.get(0).name()).isEqualTo("carol");
        assertThat(users.get(0).signupApplication()).isNull();
        assertThat(users.get(0).createdTime()).isNull();
    }

    @Test
    void toNewUser_returnsNull_forNonMapOrNamelessRow() {
        assertThat(CasdoorClient.toNewUser("not-a-map")).isNull();
        assertThat(CasdoorClient.toNewUser(Map.of("signupApplication", "app"))).isNull();
        assertThat(CasdoorClient.toNewUser(Map.of("name", "   "))).isNull();
    }

    @Test
    void toNewUser_mapsAllFields() {
        CasdoorClient.NewUser user = CasdoorClient.toNewUser(Map.of(
                "name", "dave",
                "signupApplication", "app-google",
                "createdTime", "2026-06-19T08:00:00Z"));
        assertThat(user).isEqualTo(
                new CasdoorClient.NewUser("dave", "app-google", "2026-06-19T08:00:00Z"));
    }
}
