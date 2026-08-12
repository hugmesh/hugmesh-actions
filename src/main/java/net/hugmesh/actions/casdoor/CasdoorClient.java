package net.hugmesh.actions.casdoor;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Thin reactive wrapper around the parts of the Casdoor admin API
 * this service needs. One instance per Casdoor server — the sync
 * action holds two (hugmesh.systems + hugmesh.games) and walks
 * users between them.
 *
 * <p>Authentication: HTTP Basic with {@code client-id} +
 * {@code client-secret}. Casdoor's admin API accepts both
 * {@code Authorization: Basic} and a query-string variant; Basic
 * keeps the credentials off the access log.
 *
 * <p>This is a deliberately small surface — we don't need every
 * Casdoor admin operation, only the primitives the sync action
 * composes:
 *
 * <ul>
 *   <li>{@link #listUsersInRole(String)} — flattened walk of a
 *       role's members.</li>
 *   <li>{@link #getRoleMembers(String)} — fetch a role's full
 *       member list (Casdoor's role.users field).</li>
 *   <li>{@link #setRoleMembers(String, List)} — replace a role's
 *       whole member list (Casdoor has no "add/remove one member"
 *       call).</li>
 * </ul>
 */
public class CasdoorClient {

    private final WebClient http;
    private final String organization;
    private final String label;

    public CasdoorClient(String baseUrl, String clientId, String clientSecret,
                          String organization, String label) {
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        this.http = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
        this.organization = organization;
        this.label = label;
    }

    /**
     * Test seam — inject a pre-built {@link WebClient} (e.g. backed by a
     * stubbed {@code ExchangeFunction}) so a test can drive the REAL
     * endpoint logic against canned Casdoor responses without a live
     * server. Mirrors {@code GitHubClient(WebClient)}.
     */
    CasdoorClient(WebClient http, String organization, String label) {
        this.http = http;
        this.organization = organization;
        this.label = label;
    }

    /**
     * A newly-created Casdoor user, as observed by the OIDC default-role
     * registration probe. {@code signupApplication} is the Casdoor field
     * carrying which signup application (and thus, via the configurable
     * provider-map, which OIDC provider) the user came through;
     * {@code createdTime} is Casdoor's RFC-3339-ish creation timestamp
     * string (parsed by the probe within its lookback window).
     */
    public record NewUser(String name, String signupApplication, String createdTime) {}

    /** Human-friendly tag for log lines — "systems" / "games". */
    public String label() { return label; }
    public String organization() { return organization; }

    /**
     * Read the role's {@code users} array. Each entry is a Casdoor
     * user identifier in {@code <organization>/<username>} form.
     */
    @SuppressWarnings("unchecked")
    public Mono<List<String>> getRoleMembers(String roleName) {
        return http.get()
                .uri(uri -> uri.path("/api/get-role")
                        .queryParam("id", organization + "/" + roleName)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    Object data = body.get("data");
                    if (data instanceof Map<?, ?> m) {
                        Object users = m.get("users");
                        if (users instanceof List<?> list) {
                            return (List<String>) list;
                        }
                    }
                    return List.<String>of();
                });
    }

    public Flux<String> listUsersInRole(String roleName) {
        return getRoleMembers(roleName).flatMapMany(Flux::fromIterable);
    }

    /**
     * Replace the role's user list (Casdoor's role-update endpoint is
     * authoritative — there's no "add one member" call). The action
     * computes the desired list and POSTs it whole.
     */
    @SuppressWarnings("unchecked")
    public Mono<Boolean> setRoleMembers(String roleName, List<String> users) {
        return http.post()
                .uri(uri -> uri.path("/api/update-role")
                        .queryParam("id", organization + "/" + roleName)
                        .build())
                .bodyValue(Map.of(
                        "owner", organization,
                        "name",  roleName,
                        "users", users))
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> "ok".equals(body.get("status")));
    }

    /**
     * Resolve a username in this organization to the Casdoor user's stable
     * {@code id} (the UUID the OAuth tokens carry as the JWT {@code sub}
     * claim — live-verified against Casdoor: {@code /api/get-user} returns
     * the same {@code id} a password/client-credentials token presents as
     * {@code sub}). The notification writer needs this because user-bff's
     * notification store is keyed by JWT subject, NOT by username.
     *
     * <p>Endpoint: {@code GET /api/get-user?id=<org>/<username>}. Resolves
     * EMPTY (not an error) when the user does not exist or the response
     * carries no usable {@code data.id}, so a caller can treat "unknown
     * user" as skip rather than failure.
     */
    public Mono<String> getUserId(String username) {
        return http.get()
                .uri(uri -> uri.path("/api/get-user")
                        .queryParam("id", organization + "/" + username)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(body -> {
                    Object data = body.get("data");
                    if (data instanceof Map<?, ?> m
                            && m.get("id") instanceof String id
                            && !id.isBlank()) {
                        return Mono.just(id);
                    }
                    return Mono.empty();
                });
    }

    /**
     * Read the most recently created users in this organization, newest
     * first. Backs the OIDC default-role registration probe: it asks
     * Casdoor for the {@code limit} newest users (sorted by
     * {@code created_time} descending) and the probe diffs them against
     * its process-local seen-set to find genuinely-new signups.
     *
     * <p>READ-ONLY — no write, no role mutation. Tolerant of a
     * missing / non-list {@code data} field (returns an empty list).
     * Each row maps to a {@link NewUser}; rows missing {@code name} are
     * dropped (a user with no name is meaningless to the first-login
     * call).
     *
     * <p>Endpoint: {@code GET /api/get-sorted-users?owner=<org>
     * &sorter=created_time&order=descend&limit=<limit>}.
     */
    public Mono<List<NewUser>> listNewUsers(int limit) {
        int effectiveLimit = limit > 0 ? limit : 1;
        return http.get()
                .uri(uri -> uri.path("/api/get-sorted-users")
                        .queryParam("owner", organization)
                        .queryParam("sorter", "created_time")
                        .queryParam("order", "descend")
                        .queryParam("limit", effectiveLimit)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                // Map the response's `data` array to NewUser rows, tolerating a
                // missing / non-list field (empty list).
                .map(body -> toNewUsers(body.get("data")));
    }

    /** Convert the {@code data} field of a get-sorted-users response into
     *  {@link NewUser} rows. Tolerant of a missing / non-list value
     *  (returns an empty list); rows missing a usable {@code name} are
     *  dropped. */
    static List<NewUser> toNewUsers(Object data) {
        if (!(data instanceof List<?> rows)) {
            return List.of();
        }
        java.util.List<NewUser> out = new java.util.ArrayList<>();
        for (Object row : rows) {
            NewUser user = toNewUser(row);
            // Skip nameless rows — a user with no name is meaningless to the
            // first-login call the probe makes.
            if (user != null) {
                out.add(user);
            }
        }
        return out;
    }

    /** Map a single get-sorted-users row to a {@link NewUser}, or
     *  {@code null} when the row is not a map or carries no usable
     *  {@code name}. */
    static NewUser toNewUser(Object row) {
        if (!(row instanceof Map<?, ?> m)) {
            return null;
        }
        Object name = m.get("name");
        if (!(name instanceof String n) || n.isBlank()) {
            return null;
        }
        Object signup = m.get("signupApplication");
        Object created = m.get("createdTime");
        return new NewUser(
                n,
                signup instanceof String s ? s : null,
                created instanceof String c ? c : null);
    }
}
