package net.hugmesh.actions.casdoor;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin reactive wrapper around the parts of the Casdoor admin API
 * this service needs. One instance per Casdoor server — the sync
 * action holds two (hugmesh.net + hugmesh.games) and walks
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
 *       member list by read-modify-write of the whole role object
 *       (Casdoor has no "add/remove one member" call, and a partial
 *       update-role body resets every other column).</li>
 *   <li>{@link #getUser(String)} / {@link #findUsersByProperty(String, String)}
 *       — resolve the explicit cross-realm link the GM sync maps
 *       through.</li>
 * </ul>
 */
public class CasdoorClient {

    private static final Logger log = LoggerFactory.getLogger(CasdoorClient.class);

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
     * user identifier in {@code <organization>/<username>} form. A role
     * that does not exist reads as no members. A REJECTED read is an error,
     * never an empty list: Casdoor answers a bad credential with HTTP 200
     * and {@code status:"error"} (measured on 3.80.0), and every caller
     * here diffs against this list, so "empty" would mean "revoke everyone".
     */
    public Mono<List<String>> getRoleMembers(String roleName) {
        return getRole(roleName)
                .map(CasdoorClient::membersOf)
                .defaultIfEmpty(List.of());
    }

    /** The {@code users} of a role object from {@link #getRole}. */
    @SuppressWarnings("unchecked")
    public static List<String> membersOf(Map<String, Object> role) {
        return role.get("users") instanceof List<?> list ? (List<String>) list : List.of();
    }

    /** Fail unless Casdoor reported {@code status:"ok"} (it uses HTTP 200 for errors). */
    private Map<?, ?> requireOk(Map<?, ?> body, String what) {
        if (!"ok".equals(body.get("status"))) {
            throw new IllegalStateException(what + " on " + label + " Casdoor failed: " + body.get("msg"));
        }
        return body;
    }

    public Flux<String> listUsersInRole(String roleName) {
        return getRoleMembers(roleName).flatMapMany(Flux::fromIterable);
    }

    /**
     * Read the role object exactly as Casdoor returns it ({@code data} of
     * {@code /api/get-role}). Resolves EMPTY when the role does not exist:
     * Casdoor 3.80.0 answers {@code status:"ok", data:null} for a missing
     * role. Errors when Casdoor rejects the read.
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getRole(String roleName) {
        return http.get()
                .uri(uri -> uri.path("/api/get-role")
                        .queryParam("id", organization + "/" + roleName)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> requireOk(body, "get-role " + roleName))
                .flatMap(body -> body.get("data") instanceof Map<?, ?> m
                        ? Mono.just((Map<String, Object>) m)
                        : Mono.<Map<String, Object>>empty());
    }

    /**
     * Replace the role's user list and nothing else. Casdoor has no
     * "add one member" call, and its {@code update-role} writes EVERY
     * column of the posted object. Measured on casbin/casdoor:3.80.0
     * (the version the cluster pins): posting a partial
     * {@code {owner,name,users}} body set {@code isEnabled=false} and
     * cleared {@code displayName}, {@code description}, {@code roles} and
     * {@code domains}. So this is a read-modify-write:
     *
     * <ol>
     *   <li>GET the full role. A missing role is an ERROR (the role must
     *       be seeded; this client never creates one).</li>
     *   <li>Refuse to write a role whose {@code isEnabled} is not
     *       {@code true}: the operator disabled it, and a write would not
     *       change that (resolves {@code false}, no POST).</li>
     *   <li>Copy the object, replace only {@code users}, POST it whole.</li>
     *   <li>Read the role back. Resolves {@code true} only when
     *       {@code status} was {@code ok}, the read-back member set equals
     *       {@code users}, and {@code isEnabled} is still {@code true}.
     *       Casdoor answers {@code data:"Affected"} even when nothing
     *       changed, so the response alone proves nothing.</li>
     * </ol>
     */
    public Mono<Boolean> setRoleMembers(String roleName, List<String> users) {
        String id = organization + "/" + roleName;
        return getRole(roleName)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "role " + id + " does not exist on " + label + " Casdoor; seed it first")))
                .flatMap(role -> {
                    if (!Boolean.TRUE.equals(role.get("isEnabled"))) {
                        log.warn("[casdoor:{}] role {} is disabled; refusing to write its members", label, id);
                        return Mono.just(false);
                    }
                    Map<String, Object> next = new LinkedHashMap<>(role);
                    next.put("users", List.copyOf(users));
                    return http.post()
                            .uri(uri -> uri.path("/api/update-role")
                                    .queryParam("id", id)
                                    .build())
                            .bodyValue(next)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .flatMap(body -> {
                                if (!"ok".equals(body.get("status"))) {
                                    log.warn("[casdoor:{}] update-role {} refused: {}", label, id, body.get("msg"));
                                    return Mono.just(false);
                                }
                                return getRole(roleName)
                                        .map(after -> confirmWrite(id, after, users))
                                        .defaultIfEmpty(false);
                            });
                });
    }

    private boolean confirmWrite(String id, Map<String, Object> after, List<String> wanted) {
        Object enabled = after.get("isEnabled");
        Object got = after.get("users");
        Set<Object> gotSet = got instanceof List<?> l ? new HashSet<>(l) : Set.of();
        boolean membersMatch = gotSet.equals(new HashSet<>(wanted));
        if (!Boolean.TRUE.equals(enabled) || !membersMatch) {
            log.error("[casdoor:{}] update-role {} read-back mismatch: isEnabled={} membersMatch={}",
                    label, id, enabled, membersMatch);
            return false;
        }
        return true;
    }

    /**
     * A Casdoor user as the GM sync needs it: the stable {@code id} (the
     * JWT {@code sub}), whether the account is usable, and its
     * {@code properties} map (admin-only by Casdoor's default account
     * items, which is what makes a property safe to use as a link).
     */
    public record User(String name, String id, boolean forbidden, boolean deleted,
                       Map<String, String> properties) {
        public boolean usable() { return !forbidden && !deleted && id != null && !id.isBlank(); }
    }

    /**
     * Read one user by username in this organization. Resolves EMPTY
     * when the user does not exist (Casdoor answers {@code data:null});
     * errors when Casdoor rejects the read.
     */
    public Mono<User> getUser(String username) {
        return http.get()
                .uri(uri -> uri.path("/api/get-user")
                        .queryParam("id", organization + "/" + username)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> requireOk(body, "get-user " + username))
                .flatMap(body -> Mono.justOrEmpty(toUser(body.get("data"))));
    }

    /**
     * Find the users in this organization whose {@code properties[key]}
     * equals {@code value} EXACTLY. Casdoor's {@code field}/{@code value}
     * filter on {@code get-users} is a SQL {@code LIKE %value%} over the
     * serialized properties column, so it is used only to narrow the page;
     * the exact key/value match is done here.
     */
    public Mono<List<User>> findUsersByProperty(String key, String value) {
        return http.get()
                .uri(uri -> uri.path("/api/get-users")
                        .queryParam("owner", organization)
                        .queryParam("p", 1)
                        .queryParam("pageSize", 50)
                        .queryParam("field", "properties")
                        .queryParam("value", value)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> requireOk(body, "get-users"))
                .map(body -> {
                    List<User> out = new ArrayList<>();
                    if (body.get("data") instanceof List<?> rows) {
                        for (Object row : rows) {
                            User u = toUser(row);
                            if (u != null && value.equals(u.properties().get(key))) {
                                out.add(u);
                            }
                        }
                    }
                    return out;
                });
    }

    static User toUser(Object data) {
        if (!(data instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || name.isBlank()) {
            return null;
        }
        Map<String, String> props = new LinkedHashMap<>();
        if (m.get("properties") instanceof Map<?, ?> p) {
            p.forEach((k, v) -> {
                if (k instanceof String ks && v instanceof String vs) props.put(ks, vs);
            });
        }
        return new User(name,
                m.get("id") instanceof String id ? id : null,
                Boolean.TRUE.equals(m.get("isForbidden")),
                Boolean.TRUE.equals(m.get("isDeleted")),
                Map.copyOf(props));
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
