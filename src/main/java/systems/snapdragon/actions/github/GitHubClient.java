package systems.snapdragon.actions.github;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin reactive wrapper over the three GitHub REST endpoints the
 * org-admin auto-grant needs:
 *
 * <ul>
 *   <li>{@code GET /user}              — resolve the authenticated user
 *       (login, id) from a Bearer token.</li>
 *   <li>{@code GET /user/orgs}         — list the orgs the token's
 *       owner is a member of.</li>
 *   <li>{@code GET /user/memberships/orgs/{org}} — the user's role in
 *       a specific org (returns {@code admin} / {@code member}).</li>
 *   <li>{@code GET /user/repos?affiliation=owner,collaborator,
 *       organization_member} — paginated walk of every repo the user
 *       can reach. Each row carries a {@code permissions} object —
 *       {@code admin: true} flags the rows we care about.</li>
 * </ul>
 *
 * <p>Auth: GitHub Bearer token (the OAuth access_token from the
 * federated registration flow). The token comes from the
 * snapdragon.games registration remote — see the
 * "External OAuth registration" roadmap item — or from a
 * one-shot service-account PAT when running the scheduled
 * reconciliation.
 */
public class GitHubClient {

    private final WebClient http;

    public GitHubClient(String baseUrl) {
        this.http = WebClient.builder()
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? "https://api.github.com" : baseUrl)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public record User(String login, Long id) {}
    public record OrgMembership(String org, String role) {}
    public record RepoPermission(String fullName, boolean admin) {}

    public Mono<User> currentUser(String bearer) {
        return http.get()
                .uri("/user")
                .headers(h -> h.setBearerAuth(bearer))
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> new User(
                        (String) body.get("login"),
                        body.get("id") instanceof Number n ? n.longValue() : null));
    }

    /** True iff the user is an admin of the named org. */
    public Mono<Boolean> isOrgAdmin(String bearer, String org) {
        return http.get()
                .uri("/user/memberships/orgs/{org}", org)
                .headers(h -> h.setBearerAuth(bearer))
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> "admin".equalsIgnoreCase((String) body.get("role")))
                .onErrorReturn(false);    // 404 = not a member of that org
    }

    /** Every repo the user can reach (owner / collaborator / member),
     *  flagged with whether they hold admin permission. Paginated
     *  walk — GitHub caps page size at 100 so we keep requesting
     *  until a page comes back empty. */
    public Flux<RepoPermission> reachableRepos(String bearer) {
        return reposPage(bearer, 1);
    }

    @SuppressWarnings("unchecked")
    private Flux<RepoPermission> reposPage(String bearer, int page) {
        final int pageSize = 100;
        return http.get()
                .uri(uri -> uri.path("/user/repos")
                        .queryParam("per_page", pageSize)
                        .queryParam("page", page)
                        .queryParam("affiliation", "owner,collaborator,organization_member")
                        .build())
                .headers(h -> h.setBearerAuth(bearer))
                .retrieve()
                .bodyToMono(List.class)
                .flatMapMany(rows -> {
                    if (rows == null || rows.isEmpty()) return Flux.empty();
                    Flux<RepoPermission> thisPage = Flux.fromIterable((List<Map<String, Object>>) rows)
                            .map(row -> {
                                String fullName = (String) row.get("full_name");
                                Object permsObj = row.get("permissions");
                                boolean admin = false;
                                if (permsObj instanceof Map<?, ?> perms) {
                                    admin = Boolean.TRUE.equals(perms.get("admin"));
                                }
                                return new RepoPermission(fullName, admin);
                            });
                    if (rows.size() < pageSize) return thisPage;
                    return thisPage.concatWith(reposPage(bearer, page + 1));
                });
    }

    /** Returns the set of Snapdragon-affiliated orgs the user has
     *  any admin foothold in — either as an org admin OR as an admin
     *  of at least one repo owned by the org. Used by the auto-grant
     *  rule. */
    public Mono<Set<String>> snapdragonAdminOrgs(String bearer, Set<String> snapdragonOrgNames) {
        Mono<Set<String>> viaOrgRole = Flux.fromIterable(snapdragonOrgNames)
                .filterWhen(org -> isOrgAdmin(bearer, org))
                .collect(java.util.stream.Collectors.toSet());
        Mono<Set<String>> viaRepoAdmin = reachableRepos(bearer)
                .filter(GitHubClient.RepoPermission::admin)
                .map(r -> r.fullName().split("/", 2)[0])
                .filter(snapdragonOrgNames::contains)
                .collect(java.util.stream.Collectors.toSet());
        return viaOrgRole.zipWith(viaRepoAdmin, (a, b) -> {
            java.util.HashSet<String> union = new java.util.HashSet<>(a);
            union.addAll(b);
            return java.util.Set.copyOf(union);
        });
    }
}
