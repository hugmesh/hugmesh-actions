package systems.snapdragon.actions.casdoor;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Thin reactive wrapper around the parts of the Casdoor admin API
 * this service needs. One instance per Casdoor server — the sync
 * action holds two (snapdragon.systems + snapdragon.games) and walks
 * users between them.
 *
 * <p>Authentication: HTTP Basic with {@code client-id} +
 * {@code client-secret}. Casdoor's admin API accepts both
 * {@code Authorization: Basic} and a query-string variant; Basic
 * keeps the credentials off the access log.
 *
 * <p>This is a deliberately small surface — we don't need every
 * Casdoor admin operation, only the four primitives the sync action
 * composes:
 *
 * <ul>
 *   <li>{@link #listUsersInRole(String, String)} — paginated walk of
 *       a role's members.</li>
 *   <li>{@link #getRole(String, String)} — fetch a role's full
 *       member list (Casdoor's role.users field).</li>
 *   <li>{@link #addRoleMember(String, String, String)} — append to
 *       role.users (idempotent — Casdoor dedups).</li>
 *   <li>{@link #removeRoleMember(String, String, String)} — remove
 *       from role.users.</li>
 * </ul>
 */
public class CasdoorClient {

    private final WebClient http;
    private final String organization;
    private final String label;

    public CasdoorClient(String baseUrl, String clientId, String clientSecret,
                          String organization, String label) {
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes());
        this.http = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
        this.organization = organization;
        this.label = label;
    }

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
}
