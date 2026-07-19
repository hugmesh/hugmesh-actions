package systems.hugmesh.actions.github;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for the package-private helpers
 * {@link GitHubClient#toRepoPermission(Map)} and {@link GitHubClient#union(Set, Set)}
 * extracted out of {@code reposPage} / {@code hugmeshAdminOrgs} during the
 * Convention H reactive de-nesting. They pin the EXACT behavior of the
 * former inline {@code .map} / {@code zipWith} lambdas — nested
 * {@code permissions.admin} unpacking and the two-path admin-reach union —
 * so the extraction is provably behavior-preserving.
 */
class GitHubClientHelperTest {

    @Test
    void toRepoPermission_readsAdminFlagFromNestedPermissions() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("full_name", "HugMesh/hugmesh-actions");
        row.put("permissions", Map.of("admin", true, "push", true));

        GitHubClient.RepoPermission perm = GitHubClient.toRepoPermission(row);

        assertThat(perm.fullName()).isEqualTo("HugMesh/hugmesh-actions");
        assertThat(perm.admin()).isTrue();
    }

    @Test
    void toRepoPermission_falseAdmin_whenFlagAbsentOrFalse() {
        GitHubClient.RepoPermission pushOnly = GitHubClient.toRepoPermission(Map.of(
                "full_name", "HugMesh/repo",
                "permissions", Map.of("admin", false, "push", true)));
        assertThat(pushOnly.admin()).isFalse();

        GitHubClient.RepoPermission noPerms = GitHubClient.toRepoPermission(Map.of(
                "full_name", "HugMesh/repo"));
        assertThat(noPerms.admin()).isFalse();
    }

    @Test
    void toRepoPermission_falseAdmin_whenPermissionsNotAMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("full_name", "HugMesh/repo");
        row.put("permissions", "garbage");
        assertThat(GitHubClient.toRepoPermission(row).admin()).isFalse();
    }

    @Test
    void union_mergesBothReachPaths_withoutDuplicates() {
        Set<String> viaOrgRole = Set.of("Acme", "Acme2");
        Set<String> viaRepoAdmin = Set.of("Acme2", "Other");

        assertThat(GitHubClient.union(viaOrgRole, viaRepoAdmin))
                .containsExactlyInAnyOrder("Acme", "Acme2", "Other");
    }

    @Test
    void union_handlesEmptyInputs() {
        assertThat(GitHubClient.union(Set.of(), Set.of())).isEmpty();
        assertThat(GitHubClient.union(Set.of("a"), Set.of()))
                .containsExactly("a");
        assertThat(GitHubClient.union(Set.of(), Set.of("b")))
                .containsExactly("b");
    }
}
